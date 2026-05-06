package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationException;
import com.marketdata.sdk.internal.Configuration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Specifically exercises the SDK requirements §5 contract: when {@code validateOnStartup=true} (the
 * default) and the client has a token, the constructor must call {@code GET /user/} and propagate
 * any {@link AuthenticationException} or other failure directly to the caller.
 */
class MarketDataClientStartupValidationTest {

  private HttpServer server;
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

  private MarketDataClient.Builder builder() {
    return MarketDataClient.builder()
        .apiKey("test-key")
        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    // validateOnStartup left at its default (true) — that's the whole point.
  }

  @Test
  void validatesByCallingUserAtConstruction() {
    handler.setResponse(
        200,
        "{\"x-ratelimit-requests-remaining\":99999,"
            + "\"x-ratelimit-requests-limit\":100000,"
            + "\"x-options-data-permissions\":\"\"}",
        List.of(
            new String[] {"x-api-ratelimit-limit", "100000"},
            new String[] {"x-api-ratelimit-remaining", "99999"},
            new String[] {"x-api-ratelimit-reset", "1735689600"},
            new String[] {"x-api-ratelimit-consumed", "0"}));

    try (var client = builder().build()) {
      assertThat(client.isValidateOnStartup()).isTrue();
      assertThat(handler.callsTo("/user/")).isEqualTo(1);
      // §8.1: the rate-limit snapshot is populated as a side-effect of the startup call.
      assertThat(client.getRateLimits()).isNotNull();
      assertThat(client.getRateLimits().limit()).isEqualTo(100_000L);
    }
  }

  @Test
  void invalidTokenFailsConstructionWithAuthenticationException() {
    handler.setResponse(401, "{}");

    assertThatThrownBy(() -> builder().build())
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            t -> assertThat(((AuthenticationException) t).getRequestUrl()).contains("/user/"));
  }

  @Test
  void demoModeSkipsValidationEvenWithDefaultTrueFlag() {
    // Only meaningful when the env/.env cascade yields no token: with one present, the client
    // wouldn't enter demo mode here, so the test would be exercising the wrong code path. Skip
    // rather than fail in dev environments where a real token sits in .env.
    Assumptions.assumeTrue(
        Configuration.loadFromProcess().resolve(null, "MARKETDATA_TOKEN") == null,
        "MARKETDATA_TOKEN present in cascade — can't test demo-mode path here");

    handler.setResponse(500, "should not be called"); // would fail validation if invoked

    try (var client =
        MarketDataClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .build()) {
      assertThat(client.isDemoMode()).isTrue();
      assertThat(handler.callsTo("/user/")).isZero();
    }
  }

  @Test
  void explicitOptOutSkipsValidation() {
    handler.setResponse(500, "should not be called");

    try (var client = builder().validateOnStartup(false).build()) {
      assertThat(client.isValidateOnStartup()).isFalse();
      assertThat(handler.callsTo("/user/")).isZero();
    }
  }

  // ---------- helpers ----------

  private static final class RouteHandler implements HttpHandler {
    private final AtomicInteger userCalls = new AtomicInteger();
    private int statusCode = 200;
    private String body = "{}";
    private List<String[]> extraHeaders = List.of();

    void setResponse(int code, String body) {
      setResponse(code, body, List.of());
    }

    void setResponse(int code, String body, List<String[]> extraHeaders) {
      this.statusCode = code;
      this.body = body;
      this.extraHeaders = extraHeaders;
    }

    int callsTo(String path) {
      return path.equals("/user/") ? userCalls.get() : 0;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if ("/user/".equals(exchange.getRequestURI().getPath())) {
        userCalls.incrementAndGet();
      }
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
