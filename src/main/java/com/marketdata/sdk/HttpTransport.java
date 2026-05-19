package com.marketdata.sdk;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.RateLimitError;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

  private static final Logger LOGGER = Logger.getLogger(HttpTransport.class.getName());

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
  private final AtomicReference<@Nullable RateLimitSnapshot> latestRateLimits =
      new AtomicReference<>();

  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final @Nullable String token;

  /**
   * Canonical constructor — all dependencies explicit. Production code uses {@link
   * #withDefaults(String, String, String, String, Supplier)} which assembles real defaults; tests
   * call this directly with stubs.
   *
   * <p>The {@code statusCacheSupplier} is consulted on every {@link #executeAsync} call so {@link
   * MarketDataClient} can construct the cache <em>after</em> the transport (the cache's fetcher
   * uses the transport via {@link UtilitiesResource} — the chicken-and-egg is resolved by a
   * deferred reference). Pass {@code () -> null} when no §9.5 gate is desired (e.g. tests).
   */
  HttpTransport(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      HttpDispatcher dispatcher,
      RetryExecutor retryExecutor,
      Supplier<@Nullable StatusCache> statusCacheSupplier) {
    this.baseUrl = baseUrl;
    this.apiVersion = apiVersion;
    this.userAgent = userAgent;
    this.token = token;
    this.dispatcher = dispatcher;
    this.retryExecutor = retryExecutor;
    this.statusCacheSupplier = statusCacheSupplier;
  }

  /**
   * Production factory: assembles a real {@link HttpDispatcher} (50-permit pool + JDK {@link
   * HttpClient}) and a default {@link RetryExecutor} (4 attempts, exponential 1s→30s). Used by
   * {@link MarketDataClient}.
   */
  static HttpTransport withDefaults(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      Supplier<@Nullable StatusCache> statusCacheSupplier) {
    return new HttpTransport(
        baseUrl,
        apiVersion,
        userAgent,
        token,
        new HttpDispatcher(defaultHttpClient(), CONCURRENCY_LIMIT),
        new RetryExecutor(RetryPolicy.defaults()),
        statusCacheSupplier);
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
        () -> {
          // §10.3: pre-flight gate — if our latest snapshot says credits are exhausted, fail
          // fast without hitting the wire. RateLimitError is non-retriable per §11.2, so the
          // retry executor will surface it directly.
          RateLimitError preflight = checkRateLimitPreflight(uri);
          if (preflight != null) {
            return CompletableFuture.failedFuture(preflight);
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
   * Returns a {@link RateLimitError} when the last-known snapshot reports zero remaining credits;
   * {@code null} when the request is allowed (either credits are available or no snapshot has been
   * taken yet — the first request must reach the server to populate one).
   *
   * <p>Treats {@code remaining == 0} as exhausted regardless of whether {@code reset} has passed.
   * The snapshot only refreshes on response headers, so we have no fresh data to justify letting
   * the request through; the server will fail us anyway if quotas haven't actually reset.
   */
  private @Nullable RateLimitError checkRateLimitPreflight(URI uri) {
    RateLimitSnapshot snap = latestRateLimits.get();
    if (snap == null || snap.remaining() > 0) {
      return null;
    }
    ErrorContext context = ErrorContext.forNoResponse(uri.toString(), Instant.now());
    return new RateLimitError(
        "Rate limit exhausted: 0 requests remaining (resets at " + snap.reset() + ")", context);
  }

  private boolean cacheAllowsRetry(URI uri) {
    StatusCache cache = statusCacheSupplier.get();
    if (cache == null) {
      return true; // pre-wire state or test setup without a cache
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
    Instant now = Instant.now();
    ErrorContext context = ErrorContext.forResponse(uri.toString(), status, requestId, now);
    java.time.Duration retryAfter =
        response
            .headers()
            .firstValue("Retry-After")
            .flatMap(v -> RetryAfterHeader.parse(v, now))
            .orElse(null);
    MarketDataException ex = HttpStatusMapper.map(status, context, retryAfter);
    if (ex != null) {
      LOGGER.warning(
          () -> "Request to " + uri + " returned HTTP " + status + ": " + ex.getMessage());
      throw ex;
    }
    // Mapper only returns null for 2xx, which the branch above already handled. Belt &
    // suspenders for the impossible case so a future mapper edit can't silently swallow.
    throw new com.marketdata.sdk.exception.ServerError(
        "Unmapped status " + status + " from " + uri, context);
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
    if (!path.endsWith("/")) {
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
        sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
            .append('=')
            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        first = false;
      }
    }
    return URI.create(sb.toString());
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

  /**
   * Sync bridge for resource façades: waits on {@code future}, unwrapping {@link
   * CompletionException} so the caller sees the underlying {@link MarketDataException} directly
   * (ADR-006), and routing cancellations through {@link #asRuntime} so the surface is uniform.
   *
   * <p>One place to fix the sync semantics; every {@code public T xxx()} wrapper in a resource is
   * just {@code return joinSync(xxxAsync())}.
   */
  static <T> T joinSync(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      throw asRuntime(e.getCause());
    } catch (CancellationException e) {
      throw asRuntime(e);
    }
  }

  // Visible for tests: under the current SDK design, executeAsync always wraps failures as
  // MarketDataException so the MDE branch is the only one reached from the public surface.
  // The other two branches are defensive guardrails — extracted so they can be exercised
  // directly by tests rather than relying on a synthetic public-API path.
  static RuntimeException asRuntime(@Nullable Throwable cause) {
    if (cause instanceof MarketDataException mde) {
      return mde;
    }
    if (cause instanceof RuntimeException re) {
      return re;
    }
    return new NetworkError(
        "Unexpected failure invoking SDK",
        ErrorContext.forNoResponse("(unknown)", Instant.now()),
        cause);
  }
}
