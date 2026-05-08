package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link HttpTransport} that exercise URI shapes and status codes the public
 * resource façades don't naturally hit (status 203, trailing-slash paths). Uses the JDK's built-in
 * {@link HttpServer} to avoid any extra mocking dependencies.
 */
class HttpTransportE2ETest {

  private HttpServer server;
  private final AtomicReference<URI> capturedUri = new AtomicReference<>();
  private RouteHandler handler;

  /** Minimal record matching {@code {"value": "..."}} so we can verify a successful decode. */
  record Echo(@JsonProperty("value") String value) {}

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

  private HttpTransport newTransport() {
    int port = server.getAddress().getPort();
    return new HttpTransport("http://127.0.0.1:" + port, "v1", "test/0.0", null);
  }

  /**
   * Status 203 (Non-Authoritative Information) is treated identically to 200 by the transport —
   * decoding the body and returning the result. The check {@code status == 200 || status == 203 ||
   * status == 404} in {@code processResponse} is the only place 203 appears, and without an
   * explicit test the 203 leg is dead from JaCoCo's perspective.
   */
  @Test
  void status203IsTreatedAsSuccess() {
    handler.setResponse(203, "{\"value\":\"ok\"}");

    Echo result = newTransport().executeSync(RequestSpec.get("ping").build(), Echo.class);

    assertThat(result.value()).isEqualTo("ok");
  }

  /**
   * When the {@link RequestSpec#path()} already ends with a slash, the transport must not append
   * another one. Covers the {@code endsWith("/")} → true branch in {@code buildUri}.
   */
  @Test
  void pathEndingInSlashIsNotDoubled() {
    handler.setResponse(200, "{\"value\":\"ok\"}");

    Echo result = newTransport().executeSync(RequestSpec.get("ping/").build(), Echo.class);

    assertThat(result.value()).isEqualTo("ok");
    assertThat(capturedUri.get().getPath()).isEqualTo("/v1/ping/");
    assertThat(capturedUri.get().getPath()).doesNotContain("//");
  }

  // ---------- in-process server plumbing ----------

  private final class RouteHandler implements HttpHandler {
    private int statusCode = 200;
    private String body = "{}";

    void setResponse(int code, String body) {
      this.statusCode = code;
      this.body = body;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      capturedUri.set(exchange.getRequestURI());

      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode, bodyBytes.length);
      exchange.getResponseBody().write(bodyBytes);
      exchange.getResponseBody().close();
    }
  }
}
