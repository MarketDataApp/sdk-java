package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ServerError;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the §5 startup-validation path on {@link MarketDataClient}. Each test stands up an
 * in-process server scripted to behave like the {@code /user/} endpoint under different conditions,
 * then asserts the constructor's behavior.
 */
class MarketDataClientStartupValidationTest {

  private HttpServer server;
  private final AtomicInteger requestCount = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void route(int status, String body) {
    HttpHandler handler =
        exchange -> {
          requestCount.incrementAndGet();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.getResponseBody().close();
        };
    server.createContext("/", handler);
    server.start();
  }

  // ---------- happy path ----------

  @Test
  void validatesTokenAtStartupWhenFlagIsTrue() {
    route(200, "{\"s\":\"ok\",\"name\":\"test-user\"}");

    try (var client = new MarketDataClient("valid-token", baseUrl(), null, true)) {
      assertThat(client.isDemoMode()).isFalse();
    }

    assertThat(requestCount.get()).as("/user/ must be hit exactly once on startup").isEqualTo(1);
  }

  // ---------- failure paths ----------

  @Test
  void surfaceAuthenticationErrorImmediatelyWhenTokenIsRejected() {
    route(401, "{\"s\":\"error\",\"errmsg\":\"Invalid token\"}");

    assertThatThrownBy(() -> new MarketDataClient("bogus-token", baseUrl(), null, true))
        .isInstanceOf(AuthenticationError.class)
        .satisfies(t -> assertThat(((AuthenticationError) t).getStatusCode()).isEqualTo(401));

    assertThat(requestCount.get())
        .as("startup validation must fail fast — no retry on auth errors")
        .isEqualTo(1);
  }

  /**
   * Regression: {@code validateToken} must be single-attempt. {@code HttpTransport.executeAsync}
   * normally retries 503 with exponential backoff, so if a refactor accidentally routes the startup
   * probe through it the server would see 3 calls and (in this test) eventually succeed with a 200.
   * Forcing the test to script enough responses to satisfy a 3-attempt chain — and then asserting
   * {@code requestCount == 1} — fails determinístically the moment the path stops being
   * single-shot.
   */
  @Test
  void validateTokenDoesNotRetryTransient5xx() {
    route(503, "{}");

    assertThatThrownBy(() -> new MarketDataClient("token", baseUrl(), null, true))
        .isInstanceOf(ServerError.class)
        .satisfies(t -> assertThat(((ServerError) t).getStatusCode()).isEqualTo(503));

    assertThat(requestCount.get())
        .as(
            "validateToken must call executeOnce (not executeAsync) so a refactor that"
                + " accidentally enables retry on the startup path is caught here")
        .isEqualTo(1);
  }

  @Test
  void surfacesNetworkErrorWhenServerUnreachable() throws IOException {
    // Bind+close an ephemeral port to get a "nothing is listening" URL.
    int closedPort;
    try (java.net.ServerSocket probe =
        new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      closedPort = probe.getLocalPort();
    }

    assertThatThrownBy(
            () -> new MarketDataClient("any-token", "http://127.0.0.1:" + closedPort, null, true))
        .isInstanceOf(NetworkError.class);
  }

  // ---------- skipped paths ----------

  @Test
  void skipsValidationWhenFlagIsFalse() {
    route(401, "{}"); // would normally trip AuthenticationError if called

    assertThatCode(() -> new MarketDataClient("any-token", baseUrl(), null, false).close())
        .doesNotThrowAnyException();

    assertThat(requestCount.get()).as("validateOnStartup=false must not hit /user/").isZero();
  }

  @Test
  void skipsValidationInDemoMode() {
    // This test reaches demo mode by passing apiKey=null AND relying on the env cascade
    // having no token. If the test environment exports MARKETDATA_TOKEN, the 4-arg
    // constructor still resolves a real token — we can't synthetically force demo mode
    // from the public API.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Configuration.loadFromProcess().resolve(null, EnvVars.TOKEN) == null,
        "MARKETDATA_TOKEN present in env — can't exercise demo mode through the 4-arg ctor");

    // Server is configured to fail every call, but the constructor must not even reach it.
    route(500, "{}");

    assertThatCode(() -> new MarketDataClient(null, baseUrl(), null, true).close())
        .doesNotThrowAnyException();

    assertThat(requestCount.get()).as("demo-mode constructors must skip /user/ entirely").isZero();
  }
}
