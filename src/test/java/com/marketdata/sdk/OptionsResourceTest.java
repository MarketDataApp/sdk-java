package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.options.OptionsExpirations;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookup;
import com.marketdata.sdk.options.OptionsLookupRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OptionsResourceTest {

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
  private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (a, b) -> true);

  /** Mints a fresh transport + resource pair against the given canned HTTP client. */
  private static OptionsResource resourceWith(HttpClient client) {
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
    return new OptionsResource(transport, new JsonResponseParser());
  }

  // ---------- lookup: URL & verb ----------

  @Test
  void lookupHitsVersionedEndpoint() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL230726C00200000\"}");
    OptionsResource options = resourceWith(client);

    options.lookupAsync(OptionsLookupRequest.of("AAPL230726C00200000")).join();

    HttpRequest sent = client.captured.get(0);
    assertThat(sent.uri().toString())
        .isEqualTo("http://localhost/v1/options/lookup/AAPL230726C00200000/");
    assertThat(sent.method()).isEqualTo("GET");
  }

  @Test
  void lookupUrlEncodesSpacesAndReservedChars() {
    // Spaces → %20 (not +, which is the application/x-www-form-urlencoded dialect and would be
    // taken literally by a strict path parser). The "$" is reserved in URLs and must be encoded.
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL230726C00200000\"}");
    OptionsResource options = resourceWith(client);

    options.lookupAsync(OptionsLookupRequest.of("AAPL 7/26/23 $200 Call")).join();

    String url = client.captured.get(0).uri().toString();
    // Slashes survive verbatim — the backend regex (?P<userInput>.*) matches across them, and dates
    // like "7/26/23" are natural in the input. Mirrors Python's urllib.parse.quote() default
    // (safe="/").
    assertThat(url).isEqualTo("http://localhost/v1/options/lookup/AAPL%207/26/23%20%24200%20Call/");
  }

  // ---------- lookup: response decoding ----------

  @Test
  void lookupAsyncReturnsDecodedSymbol() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}");
    OptionsResource options = resourceWith(client);

    OptionsLookup lookup = options.lookupAsync(OptionsLookupRequest.of("anything")).join().data();

    assertThat(lookup.optionSymbol()).isEqualTo("AAPL250117C00150000");
  }

  @Test
  void lookupSyncMirrorsAsync() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}");
    OptionsResource options = resourceWith(client);

    OptionsLookup viaSync = options.lookup(OptionsLookupRequest.of("x")).data();
    assertThat(viaSync.optionSymbol()).isEqualTo("AAPL250117C00150000");
  }

  @Test
  void lookupResponseExposesIsJsonAndRequestUrl() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}");
    OptionsResource options = resourceWith(client);

    Response<OptionsLookup> resp = options.lookup(OptionsLookupRequest.of("x"));
    assertThat(resp.isJson()).isTrue();
    assertThat(resp.isCsv()).isFalse();
    assertThat(resp.isHtml()).isFalse();
    assertThat(resp.statusCode()).isEqualTo(200);
  }

  // ---------- lookup: envelope handling ----------

  @Test
  void lookupErrorEnvelopeSurfacesAsParseError() {
    // Backend returns {"s":"error","errmsg":"..."} when the input can't be parsed. The
    // deserializer turns that into a JsonMappingException which JsonResponseParser wraps as a
    // ParseError — same shape as the parallel-arrays envelope path so consumers see consistent
    // error semantics across endpoints.
    CapturingClient client =
        okWith("{\"s\":\"error\",\"errmsg\":\"Unable to parse option description\"}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.lookup(OptionsLookupRequest.of("bogus")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Unable to parse option description");
  }

  @Test
  void lookupMissingOptionSymbolFieldThrowsParseError() {
    // Strict-by-default: a server bug that drops the optionSymbol field must surface, not silently
    // produce a record with an empty string. Same reasoning as UserDeserializer / ParallelArrays.
    CapturingClient client = okWith("{\"s\":\"ok\"}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.lookup(OptionsLookupRequest.of("x")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("optionSymbol");
  }

  @Test
  void lookupNonStringOptionSymbolThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":12345}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.lookup(OptionsLookupRequest.of("x")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("optionSymbol");
  }

  // ---------- expirations: URL & params ----------

  @Test
  void expirationsHitsVersionedEndpointWithNoExtraParams() {
    // No forced ?dateformat= — §3 says the universal parameter is consumer-controlled. The typed
    // deserializer adapts to whichever format the API returns.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[1737072000],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    options.expirationsAsync(OptionsExpirationsRequest.of("AAPL")).join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).isEqualTo("http://localhost/v1/options/expirations/AAPL/");
  }

  @Test
  void expirationsAttachesStrikeAndDateFilters() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[1737072000],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    options
        .expirationsAsync(
            OptionsExpirationsRequest.builder("AAPL")
                .strike(150.0)
                .date(LocalDate.of(2024, Month.JANUARY, 17))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .isEqualTo("http://localhost/v1/options/expirations/AAPL/?strike=150.0&date=2024-01-17");
  }

  @Test
  void expirationsSkipsNullFilters() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[1737072000],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    options.expirationsAsync(OptionsExpirationsRequest.of("AAPL")).join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).isEqualTo("http://localhost/v1/options/expirations/AAPL/");
  }

  // ---------- expirations: response decoding ----------

  @Test
  void expirationsDecodesEpochsToMarketMidnights() {
    // The API serializes expiration dates as the epoch for midnight America/New_York of that day
    // (confirmed against the Python SDK fixtures + DateHelper.format_date in the backend). Derive
    // the epochs from LocalDate so the test is robust to DST shifts and never carries a "what
    // does 1737090000 mean" magic-number question.
    ZoneId et = ZoneId.of("America/New_York");
    long e1 = LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et).toEpochSecond();
    long e2 = LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et).toEpochSecond();
    long e3 = LocalDate.of(2025, Month.MARCH, 21).atStartOfDay(et).toEpochSecond(); // after DST
    long updated =
        LocalDate.of(2025, Month.JANUARY, 16).atStartOfDay(et).toEpochSecond() + 19 * 3600;

    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"expirations\":["
                + e1
                + ","
                + e2
                + ","
                + e3
                + "],\"updated\":"
                + updated
                + "}");
    OptionsResource options = resourceWith(client);

    OptionsExpirations exps = options.expirations(OptionsExpirationsRequest.of("AAPL")).data();

    assertThat(exps.expirations())
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et),
            LocalDate.of(2025, Month.MARCH, 21).atStartOfDay(et));
    assertThat(exps.expirations().get(0).getZone().getId()).isEqualTo("America/New_York");
    assertThat(exps.updated()).isNotNull();
    assertThat(exps.updated().getZone().getId()).isEqualTo("America/New_York");
    assertThat(exps.updated().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 16));
    assertThat(exps.updated().getHour()).isEqualTo(19);
  }

  @Test
  void expirationsSyncMirrorsAsync() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[1737072000],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    OptionsExpirations exps = options.expirations(OptionsExpirationsRequest.of("AAPL")).data();
    assertThat(exps.expirations()).hasSize(1);
  }

  // ---------- expirations: envelope handling ----------

  @Test
  void expirationsNoDataEnvelopeYieldsEmptyList() {
    CapturingClient client = okWith("{\"s\":\"no_data\"}");
    OptionsResource options = resourceWith(client);

    OptionsExpirations exps = options.expirations(OptionsExpirationsRequest.of("NOPE")).data();
    assertThat(exps.expirations()).isEmpty();
    assertThat(exps.updated()).isNull();
  }

  @Test
  void expirationsErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client = okWith("{\"s\":\"error\",\"errmsg\":\"Underlying not found\"}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.expirations(OptionsExpirationsRequest.of("BOGUS")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Underlying not found");
  }

  @Test
  void expirationsMissingUpdatedThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"expirations\":[1737072000]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.expirations(OptionsExpirationsRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("updated");
  }

  @Test
  void expirationsAcceptsTimestampStringFormat() {
    // dateformat=timestamp returns ISO date strings for expirations and "yyyy-MM-dd HH:mm:ss XXX"
    // for updated — the deserializer detects via JSON node type and converts to native types.
    ZoneId et = ZoneId.of("America/New_York");
    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"expirations\":[\"2025-01-17\",\"2025-02-21\"],"
                + "\"updated\":\"2025-01-16 19:00:00 -05:00\"}");
    OptionsResource options = resourceWith(client);

    OptionsExpirations exps = options.expirations(OptionsExpirationsRequest.of("AAPL")).data();
    assertThat(exps.expirations())
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et));
    assertThat(exps.updated()).isNotNull();
    assertThat(exps.updated().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 16));
    assertThat(exps.updated().getHour()).isEqualTo(19);
    assertThat(exps.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void expirationsAcceptsSpreadsheetSerialFormat() {
    // Spreadsheet serials are days since 1899-12-30 UTC. 2025-01-17 = 45674.
    ZoneId et = ZoneId.of("America/New_York");
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[45674,45709],\"updated\":45673.79166667}");
    OptionsResource options = resourceWith(client);

    OptionsExpirations exps = options.expirations(OptionsExpirationsRequest.of("AAPL")).data();
    assertThat(exps.expirations())
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et));
    assertThat(exps.updated()).isNotNull();
    assertThat(exps.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void expirationsInvalidDateCellThrowsParseError() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[\"not-a-date\"],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.expirations(OptionsExpirationsRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("non-ISO date string");
  }

  @Test
  void expirationsBooleanCellThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"expirations\":[true],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.expirations(OptionsExpirationsRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("non-string, non-numeric");
  }

  // ---------- helpers ----------

  private static CapturingClient okWith(String body) {
    return new CapturingClient(200, body.getBytes(), EMPTY_HEADERS);
  }

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
