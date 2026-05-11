package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/**
 * Exercises retry behavior. Uses a scripted {@link HttpClient} stub so a single test can drive a
 * sequence of responses (e.g. 503, 503, 200) without spinning up an in-process HTTP server or
 * waiting on real backoff durations.
 */
class HttpTransportRetryTest {

  /** Tiny response shape for body-decode assertions. */
  record Echo(@JsonProperty("value") String value) {}

  /** Retry policy with sub-millisecond delays so the suite stays under a second. */
  private static RetryPolicy fastPolicy(int maxAttempts) {
    return new RetryPolicy(maxAttempts, Duration.ofMillis(1), Duration.ofMillis(5));
  }

  private static HttpTransport newTransport(MultiResponseHttpClient client, RetryPolicy policy) {
    return new HttpTransport("http://stub.local", "v1", "test/0.0", null, client, policy);
  }

  // ---------- happy paths ----------

  @Test
  void transientServer5xxRetriesAndEventuallySucceeds() {
    MultiResponseHttpClient client =
        new MultiResponseHttpClient(
            response(503, "{}"), response(503, "{}"), response(200, "{\"value\":\"ok\"}"));

    Echo result =
        newTransport(client, fastPolicy(3))
            .executeSync(RequestSpec.get("ping").build(), Echo.class);

    assertThat(result.value()).isEqualTo("ok");
    assertThat(client.callCount()).isEqualTo(3);
  }

  @Test
  void networkFailuresRetryAndEventuallySucceed() {
    MultiResponseHttpClient client =
        new MultiResponseHttpClient(
            failedResponse(new IOException("connect refused")),
            failedResponse(new IOException("connect refused")),
            response(200, "{\"value\":\"ok\"}"));

    Echo result =
        newTransport(client, fastPolicy(3))
            .executeSync(RequestSpec.get("ping").build(), Echo.class);

    assertThat(result.value()).isEqualTo("ok");
    assertThat(client.callCount()).isEqualTo(3);
  }

  // ---------- non-retriable paths fail immediately ----------

  @Test
  void status500FailsImmediatelyWithoutRetry() {
    MultiResponseHttpClient client = new MultiResponseHttpClient(response(500, "{}"));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(ServerError.class);

    // Exactly one attempt — 500 is in the retriable status space but the spec specifically
    // excludes it (see §9: "501-599 retry; 500 no retry").
    assertThat(client.callCount()).isEqualTo(1);
  }

  @Test
  void authenticationErrorFailsImmediately() {
    MultiResponseHttpClient client = new MultiResponseHttpClient(response(401, "{}"));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(AuthenticationError.class);
    assertThat(client.callCount()).isEqualTo(1);
  }

  @Test
  void badRequestFailsImmediately() {
    MultiResponseHttpClient client = new MultiResponseHttpClient(response(400, "{}"));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(BadRequestError.class);
    assertThat(client.callCount()).isEqualTo(1);
  }

  @Test
  void rateLimitErrorFailsImmediately() {
    // Spec §9 explicitly says "Never retry rate limit errors." Even though the API may send
    // Retry-After on 429, the SDK propagates immediately rather than blocking the caller.
    MultiResponseHttpClient client = new MultiResponseHttpClient(response(429, "{}"));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(RateLimitError.class);
    assertThat(client.callCount()).isEqualTo(1);
  }

  // ---------- exhaustion ----------

  @Test
  void exhaustedRetriesPropagatesLastError() {
    // 4 stub responses — only 3 should be consumed before maxAttempts is hit and we give up.
    MultiResponseHttpClient client =
        new MultiResponseHttpClient(
            response(503, "{}"), response(503, "{}"), response(503, "{}"), response(503, "{}"));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(ServerError.class)
        .satisfies(t -> assertThat(((ServerError) t).getStatusCode()).isEqualTo(503));

    assertThat(client.callCount())
        .as("maxAttempts=3 must cap total calls — including the original attempt")
        .isEqualTo(3);
  }

  @Test
  void exhaustedRetriesOnNetworkErrorsPropagatesLastError() {
    MultiResponseHttpClient client =
        new MultiResponseHttpClient(
            failedResponse(new IOException("kaboom")),
            failedResponse(new IOException("kaboom")),
            failedResponse(new IOException("kaboom")));

    assertThatThrownBy(
            () ->
                newTransport(client, fastPolicy(3))
                    .executeSync(RequestSpec.get("ping").build(), Echo.class))
        .isInstanceOf(NetworkError.class);

    assertThat(client.callCount()).isEqualTo(3);
  }

  // ---------- permits are still conserved across retries ----------

  @Test
  void permitsReturnToPoolAfterEveryAttemptRegardlessOfOutcome() throws Exception {
    MultiResponseHttpClient client =
        new MultiResponseHttpClient(
            response(503, "{}"), response(503, "{}"), response(200, "{\"value\":\"ok\"}"));
    HttpTransport transport = newTransport(client, fastPolicy(3));

    transport.executeSync(RequestSpec.get("ping").build(), Echo.class);

    AsyncSemaphore permits = readSemaphore(transport);
    assertThat(permits.availablePermits())
        .as("after a 3-attempt retry chain, every permit must be back in the pool")
        .isEqualTo(HttpTransport.CONCURRENCY_LIMIT);
  }

  // ---------- helpers ----------

  private static AsyncSemaphore readSemaphore(HttpTransport t) throws Exception {
    java.lang.reflect.Field f = HttpTransport.class.getDeclaredField("concurrencyPermits");
    f.setAccessible(true);
    return (AsyncSemaphore) f.get(t);
  }

  private static Supplier<CompletableFuture<HttpResponse<byte[]>>> response(int code, String body) {
    return () -> CompletableFuture.completedFuture(new StubHttpResponse(code, body, Map.of()));
  }

  private static Supplier<CompletableFuture<HttpResponse<byte[]>>> failedResponse(Throwable t) {
    return () -> CompletableFuture.failedFuture(t);
  }

  /**
   * {@link HttpClient} that returns scripted responses in order. Each invocation of {@code
   * sendAsync} pops the next supplier and invokes it. Running out of script entries throws — that
   * surfaces "we retried more times than the test expected" as a clear failure.
   */
  private static final class MultiResponseHttpClient extends HttpClient {
    private final Deque<Supplier<CompletableFuture<HttpResponse<byte[]>>>> script;
    private int callCount = 0;

    @SafeVarargs
    MultiResponseHttpClient(Supplier<CompletableFuture<HttpResponse<byte[]>>>... responses) {
      this.script = new ArrayDeque<>(List.of(responses));
    }

    int callCount() {
      return callCount;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      callCount++;
      Supplier<CompletableFuture<HttpResponse<byte[]>>> next = script.pollFirst();
      if (next == null) {
        return CompletableFuture.failedFuture(
            new AssertionError("Retry overshot the test script — call #" + callCount));
      }
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) next.get();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  /** Minimal {@link HttpResponse} stub — just the bits {@code HttpTransport} reads. */
  private static final class StubHttpResponse implements HttpResponse<byte[]> {
    private final int status;
    private final byte[] body;
    private final HttpHeaders headers;

    StubHttpResponse(int status, String body, Map<String, String> headers) {
      this.status = status;
      this.body = body.getBytes(StandardCharsets.UTF_8);
      Map<String, List<String>> multi = new java.util.HashMap<>();
      headers.forEach((k, v) -> multi.put(k, new ArrayList<>(List.of(v))));
      this.headers = HttpHeaders.of(multi, (a, b) -> true);
    }

    @Override
    public int statusCode() {
      return status;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public byte[] body() {
      return body;
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("http://stub.local/v1/ping/");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
