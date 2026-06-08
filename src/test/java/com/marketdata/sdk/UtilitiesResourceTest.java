package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
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
            new RetryExecutor(NO_RETRY),
            () -> null,
            Clock.systemUTC());
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

    Map<String, String> rh = utilities.headersAsync().join().values();

    assertThat(rh)
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

    Map<String, String> rh = utilities.headers().values();

    assertThat(rh).containsEntry("x", "1");
  }

  // ---------- /user/ endpoint ----------

  @Test
  void userHitsUnversionedEndpoint() {
    // The backend mounts the user router at the API root (no /v1/ prefix), same as /status/
    // and /headers/. Hitting /v1/user/ falls through to the global 404 handler — the SDK
    // must request /user/ directly.
    CapturingClient client =
        new CapturingClient(
            200,
            ("{\"x-ratelimit-requests-remaining\":1,\"x-ratelimit-requests-limit\":2,"
                    + "\"x-options-data-permissions\":\"\"}")
                .getBytes(),
            HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    utilities.userAsync().join();

    assertThat(client.captured.get(0).uri().toString()).isEqualTo("http://localhost/user/");
  }

  @Test
  void userAsyncReturnsDecodedRecord() {
    CapturingClient client =
        new CapturingClient(
            200,
            ("{\"x-ratelimit-requests-remaining\":42,\"x-ratelimit-requests-limit\":100,"
                    + "\"x-options-data-permissions\":\"OPRA data delayed 15 minutes\"}")
                .getBytes(),
            HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    User u = utilities.userAsync().join().values();

    assertThat(u.requestsRemaining()).isEqualTo(42);
    assertThat(u.requestsLimit()).isEqualTo(100);
    assertThat(u.optionsDataPermissions()).isEqualTo("OPRA data delayed 15 minutes");
  }

  @Test
  void userSyncMirrorsAsync() {
    CapturingClient client =
        new CapturingClient(
            200,
            ("{\"x-ratelimit-requests-remaining\":7,\"x-ratelimit-requests-limit\":7,"
                    + "\"x-options-data-permissions\":\"\"}")
                .getBytes(),
            HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    User u = utilities.user().values();

    assertThat(u.requestsRemaining()).isEqualTo(7);
  }

  /**
   * The {@code /user/} endpoint's typical failure mode is "no billing plan" — surfaces as 401. The
   * sync method must unwrap it to {@link AuthenticationError} directly so {@code validateOnStartup}
   * (when wired) can catch it without digging through {@code CompletionException}.
   */
  @Test
  void user401SurfacesAuthenticationErrorDirectly() {
    CapturingClient client =
        new CapturingClient(401, new byte[0], HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    assertThatThrownBy(utilities::user).isInstanceOf(AuthenticationError.class);
  }

  // ---------- /status/ endpoint ----------

  @Test
  void statusHitsTheUnversionedRootEndpoint() {
    String body =
        "{\"s\":\"ok\",\"service\":[\"/v1/x/\"],\"status\":[\"online\"],\"online\":[true],"
            + "\"uptimePct30d\":[1.0],\"uptimePct90d\":[1.0],\"updated\":[1700000000]}";
    CapturingClient client =
        new CapturingClient(200, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    utilities.statusAsync().join();

    // /status/ is at the API root, not under /v1/.
    assertThat(client.captured.get(0).uri().toString()).isEqualTo("http://localhost/status/");
  }

  @Test
  void statusAsyncReturnsZippedServiceList() {
    String body =
        "{\"s\":\"ok\","
            + "\"service\":[\"/v1/a/\",\"/v1/b/\"],"
            + "\"status\":[\"online\",\"offline\"],"
            + "\"online\":[true,false],"
            + "\"uptimePct30d\":[1.0,0.9],"
            + "\"uptimePct90d\":[1.0,0.95],"
            + "\"updated\":[1700000000,1700000001]}";
    CapturingClient client =
        new CapturingClient(200, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    List<ServiceStatus> status = utilities.statusAsync().join().values();

    assertThat(status).hasSize(2);
    assertThat(status.get(0).service()).isEqualTo("/v1/a/");
    assertThat(status.get(0).online()).isTrue();
    assertThat(status.get(1).service()).isEqualTo("/v1/b/");
    assertThat(status.get(1).online()).isFalse();
  }

  @Test
  void statusSyncMirrorsAsync() {
    String body =
        "{\"s\":\"ok\",\"service\":[\"/v1/x/\"],\"status\":[\"online\"],\"online\":[true],"
            + "\"uptimePct30d\":[1.0],\"uptimePct90d\":[1.0],\"updated\":[1700000000]}";
    CapturingClient client =
        new CapturingClient(200, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    List<ServiceStatus> status = utilities.status().values();

    assertThat(status).hasSize(1);
  }

  // ---------- Response wrapper composition ----------

  /**
   * The resource layer is responsible for composing typed model + raw body + format into a {@link
   * MarketDataResponse}. This verifies the wiring end-to-end: the raw body is reachable via {@code
   * json()}, the request URL is preserved for support, and the format from the spec is reflected in
   * the format accessors.
   */
  @Test
  void resourceWrapsTypedDataWithRawBodyAndMetadata() {
    String body = "{\"x\":\"1\"}";
    CapturingClient client =
        new CapturingClient(200, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    UtilitiesHeadersResponse r = utilities.headers();

    assertThat(r.values()).containsEntry("x", "1");
    assertThat(r.json()).isEqualTo(body);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.isJson()).isTrue();
    assertThat(r.isNoData()).isFalse();
    assertThat(r.requestUrl().toString()).isEqualTo("http://localhost/headers/");
  }

  // ---------- §13.5 no_data convention (HTTP 404 + {"s":"no_data"}) ----------

  /**
   * When the backend returns the no_data envelope with HTTP 404, the consumer must reach {@link
   * MarketDataResponse#isNoData()} and {@link MarketDataResponse#values()} normally — no {@link
   * com.marketdata.sdk.exception.ParseError} from the parallel-array field validation. The data
   * payload is the typed model with an empty collection.
   */
  @Test
  void statusEndpointSurfaces404NoDataAsEmptyResponseInsteadOfParseError() {
    String body = "{\"s\":\"no_data\"}";
    CapturingClient client =
        new CapturingClient(404, body.getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    UtilitiesStatusResponse r = utilities.status();

    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.isNoData()).isTrue();
    assertThat(r.values()).isEmpty();
    assertThat(r.json()).isEqualTo(body);
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

  /**
   * A literal JSON {@code null} body for {@code /headers/} must surface as a {@link
   * com.marketdata.sdk.exception.ParseError} carrying the request URL, status, and id — never as a
   * raw {@code NullPointerException} from the {@link RequestHeaders} canonical constructor. The
   * {@link RequestHeadersDeserializer} pre-check converts the null token into a {@code
   * JsonMappingException}, which {@link JsonResponseParser} wraps with the support context.
   */
  @Test
  void headersJsonNullBodySurfacesParseErrorNotNpe() {
    CapturingClient client =
        new CapturingClient(200, "null".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true));
    UtilitiesResource utilities = resourceWith(client);

    assertThatThrownBy(utilities::headers)
        .isInstanceOf(com.marketdata.sdk.exception.ParseError.class)
        .hasMessageContaining("/headers/")
        .hasMessageContaining("JSON null");
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
