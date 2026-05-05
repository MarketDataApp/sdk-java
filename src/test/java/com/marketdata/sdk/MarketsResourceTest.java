package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import com.marketdata.sdk.markets.MarketStatus;
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
    return new MarketDataClient("test-key", "http://127.0.0.1:" + port, null, false);
  }

  // ---------- success paths ----------

  @Test
  void statusNoArgsHitsCanonicalUrlAndDecodesPayload() {
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
      MarketStatus result = client.markets().status();

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

  @Test
  void statusForDateBuildsDateQueryParam() {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706760000],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      MarketStatus result = client.markets().status(LocalDate.of(2024, 2, 1));

      assertThat(result.days()).hasSize(1);
      assertThat(lastRequest.get().path).isEqualTo("/v1/markets/status/");
      assertThat(lastRequest.get().query).isEqualTo("date=2024-02-01");
    }
  }

  @Test
  void statusForRangeBuildsFromAndToQueryParams() {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706673600],\"status\":[\"open\"]}", List.of());

    try (var client = newClient()) {
      client.markets().status(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 5));

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

  // ---------- async parity ----------

  @Test
  void statusAsyncReturnsSameResultAsSync() throws Exception {
    handler.setResponse(
        200, "{\"s\":\"ok\",\"date\":[1706760000],\"status\":[\"closed\"]}", List.of());

    try (var client = newClient()) {
      MarketStatus async = client.markets().statusAsync().get();
      assertThat(async.days()).hasSize(1);
      assertThat(async.days().get(0).open()).isFalse();
    }
  }

  // ---------- no-data and error paths ----------

  @Test
  void notFoundWithNoDataBodyDecodesAsEmpty() {
    handler.setResponse(404, "{\"s\":\"no_data\"}", List.of());

    try (var client = newClient()) {
      MarketStatus result = client.markets().status();
      assertThat(result.isEmpty()).isTrue();
    }
  }

  @Test
  void http401ThrowsAuthenticationError() {
    handler.setResponse(401, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.markets().status())
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

  // ---------- helpers ----------

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
