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
  void unversionedSpecOmitsTheVersionSegment() {
    // /status/ and /headers/ are documented at the API root, not under /v1/. The transport must
    // honor the spec's unversioned flag so those system endpoints reach the right URL.
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    transport.executeAsync(RequestSpec.get("status").unversioned().build()).join();

    assertThat(client.captured.get(0).uri().toString()).isEqualTo("http://localhost/status/");
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
    // Real data has remaining > 0 — otherwise the §10.3 pre-flight would block the second call.
    HttpHeaders withRl =
        TestHttpClients.headersOf(
            Map.of("x-api-ratelimit-limit", "500", "x-api-ratelimit-remaining", "100"));
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

  // ---------- §10.3 pre-flight rate-limit check ----------

  private static HttpHeaders rateLimitHeaders(int remaining) {
    return TestHttpClients.headersOf(
        Map.of(
            "x-api-ratelimit-limit", "1000",
            "x-api-ratelimit-remaining", String.valueOf(remaining),
            "x-api-ratelimit-reset", "1734036832",
            "x-api-ratelimit-consumed", "1"));
  }

  /**
   * After a response that exhausts credits, the next call must fail fast with {@link
   * RateLimitError} and never reach the HttpClient. Without §10.3 we'd waste a real request to
   * discover the same answer the snapshot already gave us.
   */
  @Test
  void preflightRejectsWhenSnapshotShowsZeroRemaining() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), rateLimitHeaders(/* remaining */ 0));
    HttpTransport transport = newTransport(client);

    // First call populates the snapshot (remaining=0) and succeeds normally.
    transport.executeAsync(RequestSpec.get("markets/status").build()).join();
    assertThat(client.captured).hasSize(1);

    // Second call should be vetoed by the pre-flight; HttpClient must not see it.
    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(com.marketdata.sdk.exception.RateLimitError.class);

    assertThat(client.captured).hasSize(1);
  }

  @Test
  void preflightAllowsWhenSnapshotShowsCreditsRemaining() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), rateLimitHeaders(/* remaining */ 42));
    HttpTransport transport = newTransport(client);

    // First call populates the snapshot.
    transport.executeAsync(RequestSpec.get("markets/status").build()).join();
    // Second call should proceed — credits still available.
    transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(client.captured).hasSize(2);
  }

  /**
   * Before any rate-limit-bearing response has arrived, the snapshot is {@code null} — the first
   * request must NOT be blocked despite there being "zero" remaining in the EMPTY sentinel. The
   * pre-flight gate has to distinguish "no data yet" from "actually exhausted".
   */
  @Test
  void preflightAllowsTheFirstRequestWhenNoSnapshotExistsYet() {
    CapturingClient client =
        new CapturingClient(200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    // No prior response → no snapshot → request proceeds.
    transport.executeAsync(RequestSpec.get("markets/status").build()).join();

    assertThat(client.captured).hasSize(1);
    assertThat(transport.getLatestRateLimits()).isNull();
  }

  // ---------- §9.4 Retry-After header ----------

  /**
   * When the server attaches a {@code Retry-After} header to a 5xx response, the resulting {@link
   * ServerError} must carry the parsed {@link Duration} so the retry policy can override its
   * calculated backoff with the server's directive.
   */
  @Test
  void serverErrorCarriesParsedRetryAfterDuration() {
    HttpHeaders headers = TestHttpClients.headersOf(Map.of("Retry-After", "7"));
    CapturingClient client = new CapturingClient(503, new byte[0], headers);
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class)
        .satisfies(
            t -> {
              ServerError se = (ServerError) t.getCause();
              assertThat(se.getRetryAfter()).contains(Duration.ofSeconds(7));
            });
  }

  @Test
  void serverErrorRetryAfterIsEmptyWhenHeaderAbsent() {
    CapturingClient client =
        new CapturingClient(503, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport = newTransport(client);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class)
        .satisfies(
            t -> {
              ServerError se = (ServerError) t.getCause();
              assertThat(se.getRetryAfter()).isEmpty();
            });
  }

  // ---------- §9.5 status-cache gate ----------

  /**
   * Even with a 5xx that the policy would retry, an "offline" entry in the cache must veto the
   * retry. The dispatcher should see exactly one call: the original attempt; no retries are
   * scheduled.
   */
  @Test
  void cacheOfflineEntryVetoesA5xxRetry() throws Exception {
    com.marketdata.sdk.utilities.ApiStatus offlineForService =
        new com.marketdata.sdk.utilities.ApiStatus(
            java.util.List.of(
                new com.marketdata.sdk.utilities.ServiceStatus(
                    "/v1/markets/status/",
                    "offline",
                    false,
                    0.5,
                    0.5,
                    java.time.Instant.EPOCH.atZone(MarketDataDates.MARKET_ZONE))));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(offlineForService),
            java.time.Clock.systemUTC());
    cache.triggerRefresh();
    // Wait for the snapshot to land — the fetcher returns a completed future, so the
    // whenComplete fires synchronously on the same thread, but be defensive.
    Thread.sleep(20);

    // Allow 4 retries so we'd retry on a 5xx — IF the cache didn't veto.
    RetryPolicy fourAttempts = new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(1));
    CapturingClient client =
        new CapturingClient(503, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport =
        new HttpTransport(
            "http://localhost",
            "v1",
            "test/0.0",
            "secret-token",
            new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
            new RetryExecutor(fourAttempts),
            () -> cache);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class);

    // The cache vetoed: exactly one HTTP dispatch, no retries scheduled.
    assertThat(client.captured).hasSize(1);
  }

  /** When the cache says online (or no entry matches), retries proceed normally. */
  @Test
  void cacheOnlineEntryAllowsNormalRetryFlow() throws Exception {
    com.marketdata.sdk.utilities.ApiStatus online =
        new com.marketdata.sdk.utilities.ApiStatus(
            java.util.List.of(
                new com.marketdata.sdk.utilities.ServiceStatus(
                    "/v1/markets/status/",
                    "online",
                    true,
                    1.0,
                    1.0,
                    java.time.Instant.EPOCH.atZone(MarketDataDates.MARKET_ZONE))));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(online), java.time.Clock.systemUTC());
    cache.triggerRefresh();
    Thread.sleep(20);

    RetryPolicy fourAttempts = new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(1));
    CapturingClient client =
        new CapturingClient(503, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    HttpTransport transport =
        new HttpTransport(
            "http://localhost",
            "v1",
            "test/0.0",
            "secret-token",
            new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
            new RetryExecutor(fourAttempts),
            () -> cache);

    assertThatThrownBy(
            () -> transport.executeAsync(RequestSpec.get("markets/status").build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class);

    // 4 attempts: initial + 3 retries (policy allows; cache doesn't veto).
    assertThat(client.captured).hasSize(4);
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
