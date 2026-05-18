package com.marketdata.sdk;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
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

  /** SDK requirements §10: fixed 99-second per-request timeout. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(99);

  /** SDK requirements §10: fixed 2-second connect timeout. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** SDK requirements §12: 50-permit global concurrency pool. */
  static final int CONCURRENCY_LIMIT = 50;

  private static final String CF_RAY = "cf-ray";

  private final HttpDispatcher dispatcher;
  private final RetryExecutor retryExecutor;
  private final AtomicReference<@Nullable RateLimitSnapshot> latestRateLimits =
      new AtomicReference<>();

  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final @Nullable String token;

  HttpTransport(String baseUrl, String apiVersion, String userAgent, @Nullable String token) {
    this(
        baseUrl,
        apiVersion,
        userAgent,
        token,
        new HttpDispatcher(defaultHttpClient(), CONCURRENCY_LIMIT),
        new RetryExecutor(RetryPolicy.defaults()));
  }

  // Package-private constructor for tests: inject a stubbed dispatcher and/or a fast retry
  // policy so tests don't hit the wire and don't wait on real backoffs.
  HttpTransport(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      HttpDispatcher dispatcher,
      RetryExecutor retryExecutor) {
    this.baseUrl = baseUrl;
    this.apiVersion = apiVersion;
    this.userAgent = userAgent;
    this.token = token;
    this.dispatcher = dispatcher;
    this.retryExecutor = retryExecutor;
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
    URI uri = buildUri(spec);
    HttpRequest request = buildHttpRequest(uri, spec.format());
    return retryExecutor.execute(
        () -> dispatcher.dispatch(request).thenApply(response -> routeAndEnvelope(response, uri)));
  }

  /**
   * Sync wrapper around {@link #executeAsync}. Per ADR-006, calls {@code .join()} and unwraps
   * {@link CompletionException} so callers see the underlying {@link MarketDataException}.
   */
  HttpResponseEnvelope executeSync(RequestSpec spec) {
    try {
      return executeAsync(spec).join();
    } catch (CompletionException e) {
      throw asRuntime(e.getCause());
    } catch (CancellationException e) {
      throw asRuntime(e);
    }
  }

  @Override
  public void close() {
    // java.net.http.HttpClient gained explicit close() in JDK 21; until the SDK's minimum
    // bumps to 21+ this is a no-op (ADR-002).
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
    ErrorContext context =
        ErrorContext.forResponse(uri.toString(), status, requestId, Instant.now());
    MarketDataException ex = HttpStatusMapper.map(status, context);
    if (ex != null) {
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
    sb.append(baseUrl).append('/').append(apiVersion).append('/').append(path);
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
