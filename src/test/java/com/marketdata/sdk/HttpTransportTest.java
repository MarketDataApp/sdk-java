package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

  /** RetryPolicy with a single attempt so each test's HTTP-call count is unambiguous. */
  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));

  private static HttpTransport newTransport(HttpClient client) {
    return new HttpTransport(
        "http://localhost",
        "v1",
        "test/0.0",
        "secret-token",
        new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
        new RetryExecutor(NO_RETRY));
  }

  // ---------- URL & header composition ----------

  @Test
  void buildsUrlWithBaseVersionPathTrailingSlashAndEncodedQuery() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    transport
        .executeAsync(
            RequestSpec.get("markets/status")
                .query("date", "2024-05-01")
                .query("country", "US")
                .build())
        .join();

    HttpRequest sent = client.captured.get(0);
    assertThat(sent.uri().toString())
        .isEqualTo("http://localhost/v1/markets/status/?date=2024-05-01&country=US");
  }

  @Test
  void sendsAuthorizationUserAgentAndAcceptHeaders() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    transport.executeAsync(RequestSpec.get("markets/status").format(Format.CSV).build()).join();

    HttpRequest sent = client.captured.get(0);
    assertThat(sent.headers().firstValue("Authorization")).contains("Bearer secret-token");
    assertThat(sent.headers().firstValue("User-Agent")).contains("test/0.0");
    assertThat(sent.headers().firstValue("Accept")).contains("text/csv");
    assertThat(sent.timeout()).contains(HttpTransport.REQUEST_TIMEOUT);
  }

  @Test
  void noAuthorizationHeaderWhenTokenIsNull() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport =
        new HttpTransport(
            "http://localhost",
            "v1",
            "test/0.0",
            null,
            new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
            new RetryExecutor(NO_RETRY));

    transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(client.captured.get(0).headers().firstValue("Authorization")).isEmpty();
  }

  @Test
  void leadingSlashInPathIsStripped() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    transport.executeAsync(RequestSpec.get("/markets/status").build()).join();

    // Defensive strip — no double slash even when the resource accidentally prepends one.
    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/markets/status/");
  }

  // ---------- response envelope ----------

  @Test
  void successReturnsEnvelopeWithBodyStatusAndRequestId() {
    HttpHeaders headers = TestHttpClients.headersOf(Map.of("cf-ray", "abc-123"));
    CapturingClient client = new CapturingClient(200, "payload".getBytes(), headers);
    HttpTransport transport = newTransport(client);

    HttpResponseEnvelope env =
        transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(new String(env.body())).isEqualTo("payload");
    assertThat(env.statusCode()).isEqualTo(200);
    assertThat(env.requestId()).isEqualTo("abc-123");
    assertThat(env.url().toString()).isEqualTo("http://localhost/v1/markets/status/");
  }

  @Test
  void status203AlsoReturnsEnvelope() {
    CapturingClient client =
        new CapturingClient(203, "cached".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    HttpResponseEnvelope env =
        transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(env.statusCode()).isEqualTo(203);
    assertThat(new String(env.body())).isEqualTo("cached");
  }

  @Test
  void status404AlsoReturnsEnvelope() {
    // The API uses 404 for "no_data" responses; the body still carries a payload that resources
    // need to inspect.
    CapturingClient client =
        new CapturingClient(
            404, "{\"s\":\"no_data\"}".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    HttpResponseEnvelope env =
        transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(env.statusCode()).isEqualTo(404);
    assertThat(new String(env.body())).isEqualTo("{\"s\":\"no_data\"}");
  }

  // ---------- status routing to typed exceptions ----------

  @Test
  void status401ThrowsAuthenticationError() {
    CapturingClient client =
        new CapturingClient(401, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(AuthenticationError.class);
  }

  @Test
  void status400ThrowsBadRequestError() {
    CapturingClient client =
        new CapturingClient(400, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(BadRequestError.class);
  }

  @Test
  void status429ThrowsRateLimitError() {
    CapturingClient client =
        new CapturingClient(429, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(RateLimitError.class);
  }

  @Test
  void status500ThrowsServerError() {
    CapturingClient client =
        new CapturingClient(500, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class);
  }

  @Test
  void status418ThrowsNotFoundFallbackOrServerError() {
    // Sanity: unmapped 4xx falls through to HttpStatusMapper's catch-all; we don't pin to
    // a specific type here, only that it surfaces as SOME MarketDataException.
    CapturingClient client =
        new CapturingClient(418, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(com.marketdata.sdk.exception.MarketDataException.class);
  }

  @Test
  void notFoundStatusIsNotThrownBecauseTheApiUsesItForNoData() {
    // Sanity: 404 must NOT route to NotFoundError — it carries a no_data body. The status
    // routing's "if 200/203/404 return envelope" branch covers this.
    CapturingClient client =
        new CapturingClient(404, "{}".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    HttpResponseEnvelope env =
        transport.executeAsync(RequestSpec.get("markets/status").build()).join();
    assertThat(env.statusCode()).isEqualTo(404);
    // Compiler-only: ensure NotFoundError exists so test wouldn't compile if removed.
    @SuppressWarnings("unused")
    Class<?> noisy = NotFoundError.class;
  }

  // ---------- rate-limit snapshot ----------

  @Test
  void rateLimitSnapshotUpdatesWhenHeadersPresent() {
    HttpHeaders headers =
        TestHttpClients.headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "13"));
    CapturingClient client = new CapturingClient(200, "ok".getBytes(), headers);
    HttpTransport transport = newTransport(client);

    transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    RateLimitSnapshot snap = transport.getLatestRateLimits();
    assertThat(snap).isNotNull();
    assertThat(snap.limit()).isEqualTo(1000);
    assertThat(snap.remaining()).isEqualTo(987);
    assertThat(snap.consumed()).isEqualTo(13);
  }

  @Test
  void rateLimitSnapshotNotClearedByResponseWithoutHeaders() {
    // First call sets a snapshot; second call returns no headers; snapshot must remain
    // populated (vs flickering to null).
    HttpHeaders withRl = TestHttpClients.headersOf(Map.of("x-api-ratelimit-limit", "500"));
    HttpHeaders empty = HttpHeaders.of(Map.of(), (a, b) -> true);
    CapturingClient client = new CapturingClient(200, "ok".getBytes(), withRl);
    HttpTransport transport = newTransport(client);

    transport.executeAsync(RequestSpec.get("markets/status").build()).join();
    RateLimitSnapshot before = transport.getLatestRateLimits();
    assertThat(before).isNotNull();

    client.nextHeaders = empty;
    transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(transport.getLatestRateLimits()).isSameAs(before);
  }

  // ---------- sync bridge ----------

  @Test
  void executeSyncReturnsEnvelopeOnSuccess() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    HttpResponseEnvelope env = transport.executeSync(RequestSpec.get("markets/status").build());

    assertThat(env.statusCode()).isEqualTo(200);
  }

  @Test
  void executeSyncUnwrapsCompletionExceptionToCause() {
    CapturingClient client =
        new CapturingClient(500, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(() -> transport.executeSync(RequestSpec.get("markets/status").build()))
        .isInstanceOf(ServerError.class); // not CompletionException, not wrapped
  }

  // ---------- stub HttpClient ----------

  /**
   * Captures every {@link HttpRequest} that flows through {@code sendAsync} and replies with a
   * canned {@link HttpResponse}. Tests can mutate {@code nextHeaders}/{@code nextBody}/{@code
   * nextStatus} between calls to drive different responses across requests.
   */
  private static final class CapturingClient extends TestHttpClients.StubHttpClient {
    final List<HttpRequest> captured = new ArrayList<>();
    int nextStatus;
    byte[] nextBody;
    HttpHeaders nextHeaders;

    CapturingClient(int status, byte[] body, HttpHeaders headers) {
      this.nextStatus = status;
      this.nextBody = body;
      this.nextHeaders = headers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      captured.add(request);
      HttpResponse<byte[]> resp =
          TestHttpClients.response(
              nextStatus, nextBody, nextHeaders, URI.create("http://localhost"));
      return (CompletableFuture) CompletableFuture.completedFuture(resp);
    }
  }
}
