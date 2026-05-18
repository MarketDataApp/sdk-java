package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.utilities.RequestHeaders;
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
import org.junit.jupiter.api.Test;

class UtilitiesResourceTest {

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));

  /** Mints a fresh transport + resource pair against the given canned HTTP client. */
  private static UtilitiesResource resourceWith(HttpClient client) {
    HttpTransport transport =
        new HttpTransport(
            "http://localhost",
            "v1",
            "test/0.0",
            "secret-token",
            new HttpDispatcher(client, HttpTransport.CONCURRENCY_LIMIT),
            new RetryExecutor(NO_RETRY));
    return new UtilitiesResource(transport, new JsonResponseParser());
  }

  // ---------- URL & verb ----------

  @Test
  void headersHitsTheUnversionedRootEndpoint() {
    CapturingClient client =
        new CapturingClient(200, "{}".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    utilities.headersAsync().join();

    HttpRequest sent = client.captured.get(0);
    // No /v1/ prefix — /headers/ is documented at the API root.
    assertThat(sent.uri().toString()).isEqualTo("http://localhost/headers/");
    assertThat(sent.method()).isEqualTo("GET");
  }

  // ---------- response decoding ----------

  @Test
  void headersAsyncReturnsDecodedRecord() {
    String body =
        "{\"accept\":\"*/*\",\"authorization\":\"Bearer ***REDACTED***\","
            + "\"cf-ray\":\"abc-123-xyz\"}";
    CapturingClient client =
        new CapturingClient(200, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    RequestHeaders rh = utilities.headersAsync().join();

    assertThat(rh.headers())
        .containsEntry("accept", "*/*")
        .containsEntry("authorization", "Bearer ***REDACTED***")
        .containsEntry("cf-ray", "abc-123-xyz");
  }

  @Test
  void headersSyncMirrorsHeadersAsync() {
    CapturingClient client =
        new CapturingClient(
            200, "{\"x\":\"1\"}".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    RequestHeaders rh = utilities.headers();

    assertThat(rh.headers()).containsEntry("x", "1");
  }

  // ---------- error surfacing through sync ----------

  /**
   * Per ADR-006: sync wrappers must unwrap {@code CompletionException} so consumers catch the
   * underlying {@link com.marketdata.sdk.exception.MarketDataException} subtype directly. A 401
   * from the server must reach the caller as {@link AuthenticationError}, not wrapped.
   */
  @Test
  void headersSyncUnwrapsAuthenticationFailureFromCompletionException() {
    CapturingClient client =
        new CapturingClient(401, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    assertThatThrownBy(utilities::headers).isInstanceOf(AuthenticationError.class);
  }

  // ---------- stub HttpClient ----------

  private static final class CapturingClient extends TestHttpClients.StubHttpClient {
    final List<HttpRequest> captured = new ArrayList<>();
    final int status;
    final byte[] body;
    final HttpHeaders headers;

    CapturingClient(int status, byte[] body, HttpHeaders headers) {
      this.status = status;
      this.body = body;
      this.headers = headers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      captured.add(request);
      HttpResponse<byte[]> resp =
          TestHttpClients.response(status, body, headers, URI.create("http://localhost"));
      return (CompletableFuture) CompletableFuture.completedFuture(resp);
    }
  }
}
