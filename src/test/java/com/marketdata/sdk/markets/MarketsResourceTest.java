package com.marketdata.sdk.markets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.RateLimits;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exercises the full resource → transport → HTTP path against an in-process {@link HttpServer} (JDK
 * built-in — no extra mock dep). Verifies URL construction, query-param encoding, response
 * decoding, error mapping, and rate-limit header parsing.
 */
class MarketsResourceTest {

  private HttpServer server;
  private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();
  private RouteHandler handler;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    handler = new RouteHandler();
    server.createContext("/", handler);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private MarketDataClient newClient() {
    int port = server.getAddress().getPort();
    return MarketDataClient.builder()
        .apiKey("test-key")
        .baseUrl("http://127.0.0.1:" + port)
        .validateOnStartup(false)
        .build();
  }

  // ---------- success paths ----------

  /**
   * The 5 paths exercised below are the load-bearing scenarios — each runs once for {@link
   * CallMode#SYNC} and once for {@link CallMode#ASYNC} so we satisfy SDK requirements §13's "tests
   * must cover both sync and async variants for every endpoint" without duplicating every single
   * mechanical case.
   */
  @ParameterizedTest
  @EnumSource(CallMode.class)
  void statusNoArgsHitsCanonicalUrlAndDecodesPayload(CallMode mode) {
    handler.setResponse(
        200,
        """
        { "s":"ok", "date":[1706673600,1706760000], "status":["open","closed"] }
        """,
        List.of(
            rateLimitHeader("limit", "50000"),
            rateLimitHeader("remaining", "49500"),
            rateLimitHeader("reset", "1735689600"),
            rateLimitHeader("consumed", "1")));

    try (var client = newClient()) {
      MarketStatus result = mode.statusNoArgs(client.markets());

      assertThat(result.days()).hasSize(2);
      assertThat(result.days().get(0).open()).isTrue();
      assertThat(result.days().get(1).open()).isFalse();

      RecordedRequest req = lastRequest.get();
      assertThat(req.path).isEqualTo("/v1/markets/status/");
      assertThat(req.query).isNull();
      assertThat(req.headers.firstValue("Authorization")).hasValue("Bearer test-key");
      assertThat(req.headers.firstValue("User-Agent"))
          .get()
          .asString()
          .startsWith("marketdata-sdk-java/");
      assertThat(req.headers.firstValue("Accept")).hasValue("application/json");

      RateLimits rl = client.getRateLimits();
      assertThat(rl).isNotNull();
      assertThat(rl.limit()).isEqualTo(50000L);
      assertThat(rl.remaining()).isEqualTo(49500L);
      assertThat(rl.consumed()).isEqualTo(1L);
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void statusForDateBuildsDateQueryParam(CallMode mode) {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706760000],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      MarketStatus result = mode.statusForDate(client.markets(), LocalDate.of(2024, 2, 1));

      assertThat(result.days()).hasSize(1);
      assertThat(lastRequest.get().path).isEqualTo("/v1/markets/status/");
      assertThat(lastRequest.get().query).isEqualTo("date=2024-02-01");
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void statusForRangeBuildsFromAndToQueryParams(CallMode mode) {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706673600],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      mode.statusForRange(client.markets(), LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 5));

      assertThat(lastRequest.get().query).isEqualTo("from=2024-01-31&to=2024-02-05");
    }
  }

  @Test
  void rangeWithSwappedBoundsThrowsIllegalArgument() {
    try (var client = newClient()) {
      assertThatThrownBy(
              () -> client.markets().status(LocalDate.of(2024, 2, 5), LocalDate.of(2024, 1, 31)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be after");
    }
  }

  // ---------- async-specific smoke ----------

  /**
   * Verifies that {@code statusAsync()} returns a real {@link
   * java.util.concurrent.CompletableFuture} usable with the standard {@code .get()} contract
   * (checked exception path). The {@code @ParameterizedTest}s above cover .join() semantics; this
   * one covers .get().
   */
  @Test
  void statusAsyncReturnsRealCompletableFuture() throws Exception {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706760000],\"status\":[\"closed\"]}", List.of());

    try (var client = newClient()) {
      MarketStatus async = client.markets().statusAsync().get();
      assertThat(async.days()).hasSize(1);
      assertThat(async.days().get(0).open()).isFalse();
    }
  }

  // ---------- no-data and error paths ----------

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void notFoundWithNoDataBodyDecodesAsEmpty(CallMode mode) {
    handler.setResponse(404, "{\"s\":\"no_data\"}", List.of());

    try (var client = newClient()) {
      MarketStatus result = mode.statusNoArgs(client.markets());
      assertThat(result.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void http401ThrowsAuthenticationError(CallMode mode) {
    handler.setResponse(401, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> mode.statusNoArgs(client.markets()))
          .isInstanceOf(AuthenticationError.class)
          .satisfies(
              t -> {
                AuthenticationError ae = (AuthenticationError) t;
                assertThat(ae.getStatusCode()).isEqualTo(401);
                assertThat(ae.getRequestUrl()).contains("/v1/markets/status/");
              });
    }
  }

  @Test
  void http429ThrowsRateLimitError() {
    handler.setResponse(429, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status()).isInstanceOf(RateLimitError.class);
    }
  }

  @Test
  void http500ThrowsServerError() {
    handler.setResponse(500, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status()).isInstanceOf(ServerError.class);
    }
  }

  // ---------- malformed responses ----------

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void garbageBodyOnSuccessProducesParseError(CallMode mode) {
    handler.setResponse(200, "this is plainly not json", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> mode.statusNoArgs(client.markets()))
          .isInstanceOf(ParseError.class)
          .hasMessageContaining("Failed to decode");
    }
  }

  @Test
  void emptyBodyOnSuccessProducesParseError() {
    handler.setResponse(200, "", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status()).isInstanceOf(ParseError.class);
    }
  }

  @Test
  void unknownStatusFieldProducesParseError() {
    handler.setResponse(200, "{\"s\":\"weird\"}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
          .isInstanceOf(ParseError.class)
          .hasMessageContaining("weird");
    }
  }

  @Test
  void responseMissingArraysProducesParseError() {
    handler.setResponse(200, "{\"s\":\"ok\"}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
          .isInstanceOf(ParseError.class)
          .hasMessageContaining("date");
    }
  }

  @Test
  void mismatchedArraySizesProduceParseError() {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706673600,1706760000],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
          .isInstanceOf(ParseError.class)
          .hasMessageContaining("different sizes");
    }
  }

  // ---------- weird headers ----------

  @Test
  void successWithoutAnyRateLimitHeadersLeavesSnapshotNull() {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706673600],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      client.markets().status();
      assertThat(client.getRateLimits()).isNull();
    }
  }

  @Test
  void partialRateLimitHeadersStillProduceSnapshot() {
    handler.setResponse(
        200,
        "{\"s\":\"ok\",\"date\":[1706673600],\"status\":[\"open\"]}",
        List.of(
            new String[] {"x-api-ratelimit-limit", "100000"},
            new String[] {"x-api-ratelimit-remaining", "99999"}));

    try (var client = newClient()) {
      client.markets().status();

      RateLimits rl = client.getRateLimits();
      assertThat(rl).isNotNull();
      assertThat(rl.limit()).isEqualTo(100_000L);
      assertThat(rl.remaining()).isEqualTo(99_999L);
      assertThat(rl.consumed()).isEqualTo(0L); // missing → defaulted
    }
  }

  @Test
  void allUnparseableRateLimitHeadersAreIgnoredAsAbsent() {
    handler.setResponse(
        200,
        "{\"s\":\"ok\",\"date\":[1706673600],\"status\":[\"open\"]}",
        List.of(
            new String[] {"x-api-ratelimit-limit", "not-a-number"},
            new String[] {"x-api-ratelimit-remaining", "still-not"},
            new String[] {"x-api-ratelimit-reset", "??"},
            new String[] {"x-api-ratelimit-consumed", "wat"}));

    try (var client = newClient()) {
      client.markets().status();
      assertThat(client.getRateLimits()).isNull();
    }
  }

  @Test
  void errorResponseWithoutCfRayProducesNullRequestId() {
    handler.setResponse(401, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
          .isInstanceOf(AuthenticationError.class)
          .satisfies(t -> assertThat(((AuthenticationError) t).getRequestId()).isNull());
    }
  }

  @Test
  void errorResponseWithCfRayPropagatesRequestId() {
    handler.setResponse(401, "{}", List.<String[]>of(new String[] {"cf-ray", "abc123-XYZ"}));

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
          .isInstanceOf(AuthenticationError.class)
          .satisfies(
              t -> assertThat(((AuthenticationError) t).getRequestId()).isEqualTo("abc123-XYZ"));
    }
  }

  // ---------- network failure (connect refused — fast-failing proxy for timeout class) ----------

  /**
   * The 99-second per-request timeout is fixed by SDK requirements §10. Forcing a real timeout in a
   * test would block for ~99 s, which we don't want. Instead we exercise the {@link NetworkError}
   * path by pointing the client at a port nothing is listening on (TCP RST → fast failure). This
   * proves the transport surfaces transport-level failures as a typed exception rather than letting
   * raw {@code IOException}s leak.
   */
  @ParameterizedTest
  @EnumSource(CallMode.class)
  void connectionRefusedProducesNetworkError(CallMode mode) {
    try (var client =
        MarketDataClient.builder()
            .apiKey("test-key")
            .baseUrl("http://127.0.0.1:1") // port 1 is privileged and rejects fast.
            .validateOnStartup(false)
            .build()) {

      assertThatThrownBy(() -> mode.statusNoArgs(client.markets()))
          .isInstanceOf(NetworkError.class)
          .satisfies(
              t -> {
                NetworkError ne = (NetworkError) t;
                assertThat(ne.getCause()).isNotNull();
                assertThat(ne.getRequestUrl()).contains("127.0.0.1:1");
              });
    }
  }

  // ---------- helpers ----------

  // CallMode (sync vs async dispatcher) lives in its own file so the integration-test source set
  // can reuse it. See CallMode.java in this same package.

  private static String[] rateLimitHeader(String suffix, String value) {
    return new String[] {"x-api-ratelimit-" + suffix, value};
  }

  private record RecordedRequest(String path, String query, java.net.http.HttpHeaders headers) {}

  private final class RouteHandler implements HttpHandler {
    private int statusCode = 200;
    private String body = "{}";
    private List<String[]> extraHeaders = List.of();

    void setResponse(int code, String body, List<String[]> extraHeaders) {
      this.statusCode = code;
      this.body = body;
      this.extraHeaders = extraHeaders;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Snapshot request shape for assertions.
      URI uri = exchange.getRequestURI();
      var headerMap = new java.util.HashMap<String, List<String>>();
      exchange.getRequestHeaders().forEach((k, v) -> headerMap.put(k, new ArrayList<>(v)));
      lastRequest.set(
          new RecordedRequest(
              uri.getPath(),
              uri.getRawQuery(),
              java.net.http.HttpHeaders.of(headerMap, (a, b) -> true)));

      for (String[] h : extraHeaders) {
        exchange.getResponseHeaders().add(h[0], h[1]);
      }
      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode, bodyBytes.length);
      exchange.getResponseBody().write(bodyBytes);
      exchange.getResponseBody().close();
    }
  }
}
