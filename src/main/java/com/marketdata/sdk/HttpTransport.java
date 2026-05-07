package com.marketdata.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.markets.MarketStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The single point of contact between resource façades and the network.
 *
 * <p>Owned by {@link MarketDataClient}, instantiated once per client. All HTTP-shaped concerns live
 * here so resources never see a {@link HttpClient}, an {@link ObjectMapper}, the concurrency
 * semaphore, or the rate-limit headers — they get a {@link RequestSpec} in and a typed domain
 * object out.
 *
 * <p>Per ADR-006 the design is async-first: {@link #executeAsync} is the canonical path; {@link
 * #executeSync} is a thin wrapper that calls {@link CompletableFuture#join()} and unwraps any
 * {@link CompletionException} so the caller sees the underlying cause directly.
 *
 * <p>Per ADR-007 wire-format deserializers are registered programmatically on the {@link
 * ObjectMapper} via a {@link SimpleModule}, so response records do not carry
 * {@code @JsonDeserialize} annotations.
 */
final class HttpTransport implements AutoCloseable {

  /** SDK requirements §10: fixed 99-second per-request timeout. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(99);

  /** SDK requirements §10: fixed 2-second connect timeout. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** SDK requirements §12: 50-permit global concurrency pool. */
  static final int CONCURRENCY_LIMIT = 50;

  private static final String CF_RAY = "cf-ray";

  private final HttpClient httpClient;
  private final ObjectMapper jsonMapper;
  private final AsyncSemaphore concurrencyPermits;
  private final AtomicReference<@Nullable RateLimits> latestRateLimits = new AtomicReference<>();

  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final @Nullable String token;

  HttpTransport(String baseUrl, String apiVersion, String userAgent, @Nullable String token) {
    this(baseUrl, apiVersion, userAgent, token, defaultHttpClient());
  }

  // Package-private constructor used by tests to inject a stubbed HttpClient
  // (e.g. one whose sendAsync throws synchronously, to verify permit release).
  HttpTransport(
      String baseUrl,
      String apiVersion,
      String userAgent,
      @Nullable String token,
      HttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.apiVersion = apiVersion;
    this.userAgent = userAgent;
    this.token = token;
    this.concurrencyPermits = new AsyncSemaphore(CONCURRENCY_LIMIT);
    this.jsonMapper = buildJsonMapper();
    this.httpClient = httpClient;
  }

  private static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /** Latest client-level rate-limit snapshot, or {@code null} if no request has succeeded yet. */
  @Nullable RateLimits getLatestRateLimits() {
    return latestRateLimits.get();
  }

  /**
   * Async-first request execution.
   *
   * <p>Acquires a concurrency permit, fires the request, parses rate-limit headers, decodes the
   * body when the status is 200/203/404 (the API returns 404 with {@code {"s":"no_data"}} as a
   * sentinel — see SDK requirements §9.1), and translates other status codes to the appropriate
   * {@link MarketDataException} subtype.
   */
  <T> CompletableFuture<T> executeAsync(RequestSpec spec, Class<T> responseType) {
    URI uri = buildUri(spec);
    HttpRequest request = buildRequest(uri);

    // ADR-007: acquire returns a CompletableFuture instead of parking the caller's thread.
    // When permits are available the future is already completed (fast path) and thenCompose
    // runs synchronously; when the pool is exhausted the future completes later, on the
    // thread that calls release() — the caller's thread is never blocked here.
    return concurrencyPermits.acquire().thenCompose(unused -> dispatch(uri, request, responseType));
  }

  private <T> CompletableFuture<T> dispatch(URI uri, HttpRequest request, Class<T> responseType) {
    CompletableFuture<HttpResponse<byte[]>> sendFuture;
    try {
      sendFuture = httpClient.sendAsync(request, BodyHandlers.ofByteArray());
    } catch (Throwable t) {
      // sendAsync threw synchronously (e.g. malformed request, internal NPE, OOM).
      // The future never formed, so whenComplete will not fire — release the permit
      // here to prevent a permanent leak that would degrade the pool to deadlock.
      concurrencyPermits.release();
      if (t instanceof Error err) {
        throw err;
      }
      return CompletableFuture.failedFuture(
          new NetworkError(
              "Request to " + uri + " failed before dispatch: " + t.getMessage(),
              new ErrorContext(null, uri.toString(), null),
              t));
    }

    return sendFuture
        .whenComplete((r, t) -> concurrencyPermits.release())
        .handle(
            (response, error) -> {
              if (error != null) {
                Throwable root = unwrap(error);
                throw new CompletionException(
                    new NetworkError(
                        "Request to " + uri + " failed: " + root.getMessage(),
                        new ErrorContext(null, uri.toString(), null),
                        root));
              }
              latestRateLimits.set(RateLimitHeaders.parse(response.headers()));
              return processResponse(response, responseType, uri.toString());
            });
  }

  /**
   * Sync wrapper around {@link #executeAsync}. Per ADR-006, calls {@code .join()} and unwraps
   * {@link CompletionException} so callers see the underlying {@link MarketDataException} directly.
   */
  <T> T executeSync(RequestSpec spec, Class<T> responseType) {
    try {
      return executeAsync(spec, responseType).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof MarketDataException mde) {
        throw mde;
      }
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new NetworkError("Unexpected failure invoking SDK", ErrorContext.empty(), cause);
    }
  }

  @Override
  public void close() {
    // java.net.http.HttpClient gained explicit close() in JDK 21; until
    // the SDK's minimum bumps to 21+ this is a no-op (ADR-002).
  }

  private <T> T processResponse(HttpResponse<byte[]> response, Class<T> responseType, String url) {
    int status = response.statusCode();
    String requestId = response.headers().firstValue(CF_RAY).orElse(null);

    // 200 OK + 203 Non-Authoritative + 404 (with {"s":"no_data"} body) all
    // carry a JSON payload the resource wants to decode. Other statuses
    // mean we never got a usable body — translate to a typed exception.
    if (status == 200 || status == 203 || status == 404) {
      try {
        return jsonMapper.readValue(response.body(), responseType);
      } catch (IOException e) {
        throw new ParseError(
            "Failed to decode response from " + url + ": " + e.getMessage(),
            new ErrorContext(requestId, url, status),
            e);
      }
    }
    throw HttpStatusMapper.toException(status, url, requestId);
  }

  private URI buildUri(RequestSpec spec) {
    StringBuilder sb = new StringBuilder();
    sb.append(baseUrl).append('/').append(apiVersion).append('/').append(spec.path());
    if (!spec.path().endsWith("/")) {
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

  private HttpRequest buildRequest(URI uri) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return b.build();
  }

  /**
   * Builds the {@link ObjectMapper} used to decode every wire body. Per ADR-007 the wire-format
   * deserializers register here, not via annotations on the response records.
   */
  private static ObjectMapper buildJsonMapper() {
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule wireModule = new SimpleModule("marketdata-wire");
    wireModule.addDeserializer(MarketStatus.class, new MarketStatusDeserializer());
    mapper.registerModule(wireModule);
    return mapper;
  }

  private static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }
}
