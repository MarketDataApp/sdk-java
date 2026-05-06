package com.marketdata.sdk.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.RateLimits;
import com.marketdata.sdk.exception.AuthenticationException;
import com.marketdata.sdk.exception.ParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Same shape as {@code MarketsResourceTest}: full resource → transport → in-process {@link
 * HttpServer}, parameterized over sync + async to satisfy SDK requirements §13.
 */
class UtilitiesResourceTest {

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

  // ---------- success path (sync + async) ----------

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void userHitsCanonicalUrlAndDecodesPayload(CallMode mode) {
    handler.setResponse(
        200,
        "{\"x-ratelimit-requests-remaining\":49500,"
            + "\"x-ratelimit-requests-limit\":50000,"
            + "\"x-options-data-permissions\":\"\"}",
        List.of(
            new String[] {"x-api-ratelimit-limit", "50000"},
            new String[] {"x-api-ratelimit-remaining", "49500"},
            new String[] {"x-api-ratelimit-reset", "1735689600"},
            new String[] {"x-api-ratelimit-consumed", "1"}));

    try (var client = newClient()) {
      UserInfo info = mode.user(client.utilities());

      assertThat(info.requestsLimit()).isEqualTo(50_000L);
      assertThat(info.requestsRemaining()).isEqualTo(49_500L);
      assertThat(info.optionsDataPermissions()).isEmpty();

      RecordedRequest req = lastRequest.get();
      assertThat(req.path).isEqualTo("/user/");
      assertThat(req.headers.firstValue("Authorization")).hasValue("Bearer test-key");

      // §8.1 side-effect: rate-limit headers populate the client snapshot.
      RateLimits rl = client.getRateLimits();
      assertThat(rl).isNotNull();
      assertThat(rl.limit()).isEqualTo(50_000L);
      assertThat(rl.remaining()).isEqualTo(49_500L);
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void delayedOptionsPermissionsAreRoundtripped(CallMode mode) {
    handler.setResponse(
        200,
        "{\"x-ratelimit-requests-remaining\":100,"
            + "\"x-ratelimit-requests-limit\":1000,"
            + "\"x-options-data-permissions\":\"OPRA data delayed 15 minutes\"}",
        List.of());

    try (var client = newClient()) {
      UserInfo info = mode.user(client.utilities());

      assertThat(info.optionsDataPermissions()).isEqualTo("OPRA data delayed 15 minutes");
    }
  }

  // ---------- /status/ (root path, no /v1/ prefix) ----------

  @Test
  void statusHitsRootPathNotV1() {
    handler.setResponse(
        200,
        "{\"s\":\"ok\","
            + "\"service\":[\"/v1/stocks/quotes/\"],"
            + "\"status\":[\"online\"],"
            + "\"online\":[true],"
            + "\"uptimePct30d\":[1.0],"
            + "\"uptimePct90d\":[1.0],"
            + "\"updated\":[1734036832]}",
        List.of());

    try (var client = newClient()) {
      ServiceStatus result = client.utilities().status();

      assertThat(result.services()).hasSize(1);
      assertThat(result.allOnline()).isTrue();
      // Critical: NO /v1/ prefix on the URL.
      assertThat(lastRequest.get().path).isEqualTo("/status/");
    }
  }

  @Test
  void statusAsyncHitsSamePath() throws Exception {
    handler.setResponse(
        200,
        "{\"s\":\"ok\","
            + "\"service\":[\"/v1/funds/candles/\"],"
            + "\"status\":[\"online\"],"
            + "\"online\":[true],"
            + "\"uptimePct30d\":[1.0],"
            + "\"uptimePct90d\":[1.0],"
            + "\"updated\":[1]}",
        List.of());

    try (var client = newClient()) {
      ServiceStatus result = client.utilities().statusAsync().get();
      assertThat(result.services()).hasSize(1);
      assertThat(lastRequest.get().path).isEqualTo("/status/");
    }
  }

  // ---------- /headers/ (root path, no /v1/ prefix) ----------

  @Test
  void headersHitsRootPathAndDecodesArbitraryKeys() {
    handler.setResponse(
        200,
        "{"
            + "\"accept\":\"*/*\","
            + "\"Authorization\":\"Bearer ***YKT0\","
            + "\"User-Agent\":\"marketdata-sdk-java/0.1.0-SNAPSHOT\""
            + "}",
        List.of());

    try (var client = newClient()) {
      RequestHeaders result = client.utilities().headers();

      assertThat(result.get("Authorization")).hasValue("Bearer ***YKT0");
      assertThat(result.get("user-agent")).get().asString().contains("marketdata-sdk-java");
      assertThat(lastRequest.get().path).isEqualTo("/headers/");
    }
  }

  @Test
  void headersAsyncReturnsRealCompletableFuture() throws Exception {
    handler.setResponse(200, "{\"x-test\":\"yes\"}", List.of());

    try (var client = newClient()) {
      RequestHeaders result = client.utilities().headersAsync().get();
      assertThat(result.get("x-test")).hasValue("yes");
    }
  }

  // ---------- error paths (sync + async) ----------

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void http401ThrowsAuthenticationException(CallMode mode) {
    handler.setResponse(401, "{}", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> mode.user(client.utilities()))
          .isInstanceOf(AuthenticationException.class)
          .satisfies(
              t -> {
                AuthenticationException ae = (AuthenticationException) t;
                assertThat(ae.getStatusCode()).isEqualTo(401);
                assertThat(ae.getRequestUrl()).contains("/user/");
              });
    }
  }

  @Test
  void garbageBodyOnSuccessProducesParseException() {
    handler.setResponse(200, "this is not json", List.of());

    try (var client = newClient()) {
      assertThatThrownBy(() -> client.utilities().user())
          .isInstanceOf(ParseException.class)
          .hasMessageContaining("Failed to decode");
    }
  }

  // ---------- helpers ----------

  /**
   * Local sync/async dispatcher. Lives here instead of reusing the markets-package {@code CallMode}
   * because the call signatures differ per resource — utilities only has {@code user()}, markets
   * has three overloads.
   */
  enum CallMode {
    SYNC {
      @Override
      UserInfo user(UtilitiesResource r) {
        return r.user();
      }
    },
    ASYNC {
      @Override
      UserInfo user(UtilitiesResource r) {
        try {
          return r.userAsync().join();
        } catch (CompletionException e) {
          if (e.getCause() instanceof RuntimeException re) {
            throw re;
          }
          throw e;
        }
      }
    };

    abstract UserInfo user(UtilitiesResource r);
  }

  private record RecordedRequest(String path, java.net.http.HttpHeaders headers) {}

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
      var headerMap = new java.util.HashMap<String, List<String>>();
      exchange.getRequestHeaders().forEach((k, v) -> headerMap.put(k, new ArrayList<>(v)));
      lastRequest.set(
          new RecordedRequest(
              exchange.getRequestURI().getPath(),
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
