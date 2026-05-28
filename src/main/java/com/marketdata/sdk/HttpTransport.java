package com.marketdata.sdk;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single point of contact between resource façades and the network.
 *
 * <p>Owned by {@link MarketDataClient}, instantiated once per client. Composes the URL/request
 * builders (private helpers below) with {@link HttpDispatcher} (concurrency + send) and {@link
 * RetryExecutor} (retry orchestration), and applies the API's HTTP-level status routing (200/203/
 * 404 → success envelope; 4xx/5xx → typed exception via {@link HttpStatusMapper}).
 *
 * <p>The transport is deliberately <strong>agnostic to response format</strong>. It hands back an
 * {@link HttpResponseEnvelope} of raw bytes plus metadata; resources decide whether to decode as
 * JSON, CSV, HTML, or return raw.
 *
 * <p>Per ADR-006 the design is async-first: {@link #executeAsync} is the canonical path; {@link
 * #executeSync} is a thin wrapper that calls {@link CompletableFuture#join()} and unwraps any
 * {@link CompletionException} so the caller sees the underlying {@link MarketDataException}
 * directly.
 */
final class HttpTransport implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME);

  /** SDK requirements §10: fixed 99-second per-request timeout. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(99);

  /** SDK requirements §10: fixed 2-second connect timeout. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** SDK requirements §12: 50-permit global concurrency pool. */
  static final int CONCURRENCY_LIMIT = 50;

  private static final String CF_RAY = "cf-ray";

  private final HttpDispatcher dispatcher;
  private final RetryExecutor retryExecutor;
  private final Supplier<@Nullable StatusCache> statusCacheSupplier;
  private final Clock clock;
  private final AtomicReference<@Nullable RateLimitSnapshot> latestRateLimits =
      new AtomicReference<>();

  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final @Nullable String token;

  /** URI the StatusCache's own fetcher targets; matched verbatim by cacheAllowsRetry. */
  private final URI statusEndpointUri;

  /**
   * Canonical constructor — all dependencies explicit. Production code uses {@link
   * #withDefaults(String, String, String, String, Supplier)} which assembles real defaults; tests
   * call this directly with stubs.
   *
   * <p>The {@code statusCacheSupplier} is consulted on every {@link #executeAsync} call so {@link
   * MarketDataClient} can construct the cache <em>after</em> the transport (the cache's fetcher
   * uses the transport via {@link UtilitiesResource} — the chicken-and-egg is resolved by a
   * deferred reference). Pass {@code () -> null} when no §9.5 gate is desired (e.g. tests).
   *
   * <p>The {@code clock} drives the §10.3 preflight's {@code reset}-window check; tests pass a
   * fixed clock to assert the time-based gate behavior deterministically.
   */
  HttpTransport(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      HttpDispatcher dispatcher,
      RetryExecutor retryExecutor,
      Supplier<@Nullable StatusCache> statusCacheSupplier,
      Clock clock) {
    this.baseUrl = baseUrl;
    this.apiVersion = apiVersion;
    this.userAgent = userAgent;
    this.token = token;
    this.dispatcher = dispatcher;
    this.retryExecutor = retryExecutor;
    this.statusCacheSupplier = statusCacheSupplier;
    this.clock = clock;
    // Derive from baseUrl so a path-prefixed base (e.g. https://corp/proxy) still matches the
    // /status/ self-referential bypass. Hardcoding "/status/" would silently stop working in
    // that case.
    this.statusEndpointUri = buildUri(RequestSpec.get("status").unversioned().build());
  }

  /**
   * Production factory: assembles a real {@link HttpDispatcher} (50-permit pool + JDK {@link
   * HttpClient}), a default {@link RetryExecutor} (4 attempts, exponential 1s→30s), and {@link
   * Clock#systemUTC()} for the preflight reset-window check.
   */
  static HttpTransport withDefaults(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      Supplier<@Nullable StatusCache> statusCacheSupplier) {
    Clock clock = Clock.systemUTC();
    return new HttpTransport(
        baseUrl,
        apiVersion,
        userAgent,
        token,
        new HttpDispatcher(defaultHttpClient(), CONCURRENCY_LIMIT, clock),
        new RetryExecutor(RetryPolicy.defaults()),
        statusCacheSupplier,
        clock);
  }

  private static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Latest client-level rate-limit snapshot, or {@code null} if no rate-limit-bearing response has
   * arrived yet. Successful responses without rate-limit headers do not clear it.
   */
  @Nullable RateLimitSnapshot getLatestRateLimits() {
    return latestRateLimits.get();
  }

  /**
   * Async-first request execution with retry. Returns the raw response envelope on success (HTTP
   * 200/203/404); 4xx and 5xx responses surface as the corresponding {@link MarketDataException}
   * subtype via {@link HttpStatusMapper}, possibly after retries.
   */
  CompletableFuture<HttpResponseEnvelope> executeAsync(RequestSpec spec) {
    return executeAsync(spec, retryExecutor);
  }

  /**
   * Like {@link #executeAsync(RequestSpec)}, but uses the caller's {@link RetryPolicy} instead of
   * the transport's default. Used by callers that need a different retry budget for one specific
   * call — e.g. {@link MarketDataClient}'s startup validation, which uses {@link
   * RetryPolicy#noRetry()} so a slow/down API surfaces immediately to the constructor.
   */
  CompletableFuture<HttpResponseEnvelope> executeAsync(RequestSpec spec, RetryPolicy policy) {
    return executeAsync(spec, new RetryExecutor(policy));
  }

  private CompletableFuture<HttpResponseEnvelope> executeAsync(
      RequestSpec spec, RetryExecutor executor) {
    URI uri = buildUri(spec);
    HttpRequest request = buildHttpRequest(uri, spec.format());
    RetryPolicy policy = executor.policy();
    return executor.execute(
        (attemptIdx, previousCause) -> {
          // §10.3: pre-flight gate — if our latest snapshot says credits are exhausted, fail
          // fast without hitting the wire. RateLimitError is non-retriable per §11.2, so the
          // retry executor will surface it directly.
          //
          // Exception: when the previous attempt failed with a ServerError carrying an explicit
          // Retry-After (§9.4), the server has just told us "come back at <now + retryAfter>".
          // That directive is more authoritative than our snapshot for this specific retry;
          // honoring it is exactly what §9.4 demands. Without this bypass, a 503 + Retry-After
          // after a snapshot that reports remaining=0 with a far-future reset would sabotage the
          // server-orchestrated backoff — the retry would never reach the wire.
          if (!isServerHintedRetry(previousCause)) {
            RateLimitError preflight = checkRateLimitPreflight(uri);
            if (preflight != null) {
              return CompletableFuture.failedFuture(preflight);
            }
          }
          return dispatcher
              .dispatch(request)
              .thenApply(response -> routeAndEnvelope(response, uri));
        },
        // §9.5: gate retries on retryable server errors through the /status/ cache. Even if the
        // policy says yes, an "offline" cache entry for this URI's service blocks the retry so
        // the caller fails fast instead of hammering a known-down service.
        (cause, attempt) -> policy.shouldRetry(cause, attempt) && cacheAllowsRetry(uri));
  }

  /**
   * Was the previous attempt's failure a server-side directive to come back at a specific time?
   * Only 5xx responses carrying a parsed {@code Retry-After} qualify — that's the case where the
   * server has explicitly scheduled our retry, and our local rate-limit snapshot (whose {@code
   * reset} may be unrelated and far in the future) must not veto it.
   */
  private static boolean isServerHintedRetry(@Nullable Throwable previousCause) {
    return previousCause instanceof ServerError server && server.getRetryAfter().isPresent();
  }

  /**
   * Returns a {@link RateLimitError} when the last-known snapshot reports zero remaining credits
   * <em>and</em> the snapshot's {@code reset} timestamp is still in the future. Returns {@code
   * null} when the request is allowed (credits available, no snapshot yet, or the reset window has
   * elapsed — the snapshot is stale and the next response's headers will refresh it).
   *
   * <p>Without the reset check, a single response carrying {@code remaining=0} would freeze the
   * client forever: the preflight would short-circuit every subsequent request, no request would
   * reach the wire, and the snapshot would never refresh — even after the server replenished
   * credits at the reset time.
   */
  private @Nullable RateLimitError checkRateLimitPreflight(URI uri) {
    RateLimitSnapshot snap = latestRateLimits.get();
    if (snap == null || snap.remaining() > 0) {
      return null;
    }
    Instant now = clock.instant();
    if (!now.isBefore(snap.reset())) {
      // now >= reset → window has elapsed; let the request through so the response refreshes
      // the snapshot. If the server hasn't actually replenished yet it will reject with 429,
      // which costs one round-trip — strictly less harmful than locking out indefinitely.
      return null;
    }
    ErrorContext context = ErrorContext.forNoResponse(uri.toString(), now);
    return new RateLimitError(
        "Rate limit exhausted: 0 requests remaining (resets at " + snap.reset() + ")", context);
  }

  private boolean cacheAllowsRetry(URI uri) {
    StatusCache cache = statusCacheSupplier.get();
    if (cache == null) {
      return true; // pre-wire state or test setup without a cache
    }
    // Self-referential bypass: the cache's own fetcher targets statusEndpointUri. If we
    // consulted the cache for retries of that fetch and the snapshot reported /status/ offline
    // (or any wildcard match grazed it), the retry would be blocked — and because no
    // successful fetch can land, the snapshot would stay frozen in that "offline" state
    // forever. Skip the cache for the /status/ URI so the §9.5 gate cannot trap its own
    // refresh.
    if (statusEndpointUri.equals(uri)) {
      return true;
    }
    return cache.check(uri) == StatusCache.Decision.ALLOW;
  }

  /**
   * Sync wrapper around {@link #executeAsync}. Per ADR-006, calls {@code .join()} and unwraps
   * {@link CompletionException} so callers see the underlying {@link MarketDataException}.
   */
  HttpResponseEnvelope executeSync(RequestSpec spec) {
    return joinSync(executeAsync(spec));
  }

  /** Instance bridge for resources: uses this transport's {@link Clock} for fallback errors. */
  <T> T joinSync(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      throw asRuntime(e.getCause(), clock);
    } catch (CancellationException e) {
      throw asRuntime(e, clock);
    }
  }

  @Override
  public void close() {
    // Drains the dispatcher's semaphore so pending waiters surface CancellationException instead
    // of hanging forever. java.net.http.HttpClient gained explicit close() in JDK 21; until the
    // SDK's minimum bumps to 21+ in-flight HTTP sends still run to completion (ADR-002).
    dispatcher.close();
  }

  // ---------- private helpers ----------

  /**
   * Status routing + rate-limit snapshot update. Runs inside the retry supplier so a 5xx that we'd
   * retry surfaces here as a thrown exception that {@link RetryExecutor} can catch and pass to the
   * policy.
   */
  private HttpResponseEnvelope routeAndEnvelope(HttpResponse<byte[]> response, URI uri) {
    // Only overwrite when the response carried parseable rate-limit headers — the API
    // occasionally responds without them on its own internal errors; clobbering with null
    // would make `getLatestRateLimits()` flicker.
    RateLimitSnapshot parsed = RateLimitHeaders.parse(response.headers());
    if (parsed != null) {
      latestRateLimits.set(parsed);
    }

    int status = response.statusCode();
    String requestId = response.headers().firstValue(CF_RAY).orElse(null);

    // Any 2xx is treated as success — the API uses 200 and 203 today, but a future endpoint
    // returning 201/204 should still hand the body to the resource. 404 is the API's
    // no_data convention: the body still carries a typed payload the resource interprets.
    if ((status >= 200 && status < 300) || status == 404) {
      return new HttpResponseEnvelope(response.body(), status, requestId, response.headers(), uri);
    }
    Instant now = clock.instant();
    ErrorContext context = ErrorContext.forResponse(uri.toString(), status, requestId, now);
    Duration retryAfter =
        response
            .headers()
            .firstValue("Retry-After")
            .flatMap(v -> RetryAfterHeader.parse(v, now))
            .orElse(null);
    MarketDataException ex = HttpStatusMapper.map(status, context, retryAfter);
    if (ex != null) {
      LOGGER.warning(
          () ->
              "Request to "
                  + HttpDispatcher.safeUri(uri)
                  + " returned HTTP "
                  + status
                  + ": "
                  + ex.getMessage());
      throw ex;
    }
    // Mapper only returns null for 2xx, which the branch above already handled. Belt &
    // suspenders for the impossible case so a future mapper edit can't silently swallow.
    // §16: route the URI through safeUri so getMessage() — accessible to any consumer that
    // logs the exception — never carries query strings (token, account_id, symbols, …).
    throw new ServerError(
        "Unmapped status " + status + " from " + HttpDispatcher.safeUri(uri), context);
  }

  private URI buildUri(RequestSpec spec) {
    // RequestSpec's Javadoc says path has no leading slash, but a caller mistake would produce
    // baseUrl/v1//markets/status (double slash). Strip defensively so the URL stays well-formed
    // regardless of which side of the contract the bug is on.
    String path = spec.path();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    StringBuilder sb = new StringBuilder();
    sb.append(baseUrl).append('/');
    if (spec.versioned()) {
      sb.append(apiVersion).append('/');
    }
    sb.append(path);
    if (!path.isEmpty() && !path.endsWith("/")) {
      sb.append('/');
    }
    Map<String, String> params = spec.queryParams();
    if (!params.isEmpty()) {
      sb.append('?');
      boolean first = true;
      for (Map.Entry<String, String> e : params.entrySet()) {
        if (!first) {
          sb.append('&');
        }
        sb.append(encodeQueryComponent(e.getKey()))
            .append('=')
            .append(encodeQueryComponent(e.getValue()));
        first = false;
      }
    }
    return URI.create(sb.toString());
  }

  /**
   * RFC 3986 percent-encoding for a query-string component. {@link URLEncoder} emits {@code
   * application/x-www-form-urlencoded} bytes — i.e. spaces become {@code +} — which is the wrong
   * dialect for query strings: strict servers treat {@code ?symbol=BRK A} and {@code
   * ?symbol=BRK%20A} as equivalent but {@code ?symbol=BRK+A} as a literal {@code +}. Replacing
   * {@code +} with {@code %20} after encoding is the canonical patch: {@link URLEncoder} only emits
   * {@code +} for the space character (everything else that needs encoding is already {@code %XX}),
   * so the substitution is unambiguous.
   */
  private static String encodeQueryComponent(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private HttpRequest buildHttpRequest(URI uri, Format format) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", userAgent)
            .header("Accept", format.mediaType());
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return b.build();
  }

  // Visible for tests: under the current SDK design, executeAsync always wraps failures as
  // MarketDataException so the MDE branch is the only one reached from the public surface.
  // The other two branches are defensive guardrails — extracted so they can be exercised
  // directly by tests rather than relying on a synthetic public-API path.
  static RuntimeException asRuntime(@Nullable Throwable cause, Clock clock) {
    if (cause instanceof MarketDataException mde) {
      return mde;
    }
    if (cause instanceof RuntimeException re) {
      return re;
    }
    return new NetworkError(
        "Unexpected failure invoking SDK",
        ErrorContext.forNoResponse("(unknown)", clock.instant()),
        cause);
  }
}
