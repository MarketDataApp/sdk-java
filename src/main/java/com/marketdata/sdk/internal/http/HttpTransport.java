package com.marketdata.sdk.internal.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.sdk.RateLimits;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkException;
import com.marketdata.sdk.exception.ParseException;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The single point of contact between resource façades and the network.
 *
 * <p>Owned by {@code MarketDataClient}, instantiated once per client. All HTTP-shaped concerns live
 * here so resources never see a {@link HttpClient}, an {@link ObjectMapper}, the concurrency
 * semaphore, or the rate-limit headers — they get a {@link RequestSpec} in and a typed domain
 * object out.
 *
 * <p>Per ADR-006 the design is async-first: {@link #executeAsync} is the canonical path; {@link
 * #executeSync} is a thin wrapper that calls {@link CompletableFuture#join()} and unwraps any
 * {@link CompletionException} so the caller sees the underlying cause directly.
 */
public final class HttpTransport implements AutoCloseable {

  /** SDK requirements §10: fixed 99-second per-request timeout. */
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(99);

  /** SDK requirements §10: fixed 2-second connect timeout. */
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** SDK requirements §12: 50-permit global concurrency pool. */
  public static final int CONCURRENCY_LIMIT = 50;

  private static final String CF_RAY = "cf-ray";

  private final HttpClient httpClient;
  private final ObjectMapper jsonMapper;
  private final Semaphore concurrencyPermits;
  private final AtomicReference<@Nullable RateLimits> latestRateLimits = new AtomicReference<>();

  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final @Nullable String token;

  public HttpTransport(
      String baseUrl, String apiVersion, String userAgent, @Nullable String token) {
    this.baseUrl = baseUrl;
    this.apiVersion = apiVersion;
    this.userAgent = userAgent;
    this.token = token;
    this.concurrencyPermits = new Semaphore(CONCURRENCY_LIMIT);
    // Be lenient on unknown JSON properties: the API may add new response
    // fields over time, and we don't want SDK consumers to start seeing
    // ParseException the moment a backend ships a new field. Records that
    // need every field strictly mapped opt back in via a custom
    // @JsonDeserialize anyway (see the wire-format deserializers).
    this.jsonMapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** Latest client-level rate-limit snapshot, or {@code null} if no request has succeeded yet. */
  public @Nullable RateLimits getLatestRateLimits() {
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
  public <T> CompletableFuture<T> executeAsync(RequestSpec spec, Class<T> responseType) {
    URI uri = buildUri(spec);
    HttpRequest request = buildRequest(uri);

    try {
      concurrencyPermits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CompletableFuture.failedFuture(
          new NetworkException(
              "Interrupted while waiting for a concurrency permit",
              new ErrorContext(null, uri.toString(), null),
              e));
    }

    return httpClient
        .sendAsync(request, BodyHandlers.ofByteArray())
        .whenComplete((r, t) -> concurrencyPermits.release())
        .handle(
            (response, error) -> {
              if (error != null) {
                Throwable root = unwrap(error);
                throw new CompletionException(
                    new NetworkException(
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
  public <T> T executeSync(RequestSpec spec, Class<T> responseType) {
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
      throw new NetworkException("Unexpected failure invoking SDK", ErrorContext.empty(), cause);
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
        throw new ParseException(
            "Failed to decode response from " + url + ": " + e.getMessage(),
            new ErrorContext(requestId, url, status),
            e);
      }
    }
    throw HttpStatusMapper.toException(status, url, requestId);
  }

  private URI buildUri(RequestSpec spec) {
    StringBuilder sb = new StringBuilder();
    sb.append(baseUrl).append('/');
    if (spec.versioned()) {
      sb.append(apiVersion).append('/');
    }
    sb.append(spec.path());
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

  private static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }
}
