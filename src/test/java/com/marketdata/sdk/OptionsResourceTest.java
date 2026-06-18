package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.ExpirationStrikes;
import com.marketdata.sdk.options.Greek;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.OptionsStrikesRequest;
import com.marketdata.sdk.options.StrikeFilter;
import com.marketdata.sdk.options.StrikeRange;
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
import java.time.ZonedDateTime;
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

    String lookup = options.lookupAsync(OptionsLookupRequest.of("anything")).join().values();

    assertThat(lookup).isEqualTo("AAPL250117C00150000");
  }

  @Test
  void lookupSyncMirrorsAsync() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}");
    OptionsResource options = resourceWith(client);

    String viaSync = options.lookup(OptionsLookupRequest.of("x")).values();
    assertThat(viaSync).isEqualTo("AAPL250117C00150000");
  }

  @Test
  void lookupResponseExposesIsJsonAndRequestUrl() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}");
    OptionsResource options = resourceWith(client);

    OptionsLookupResponse resp = options.lookup(OptionsLookupRequest.of("x"));
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

    OptionsExpirationsResponse expResp = options.expirations(OptionsExpirationsRequest.of("AAPL"));
    List<ZonedDateTime> exps = expResp.values();

    assertThat(exps)
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et),
            LocalDate.of(2025, Month.MARCH, 21).atStartOfDay(et));
    assertThat(exps.get(0).getZone().getId()).isEqualTo("America/New_York");
    assertThat(expResp.updated()).isNotNull();
    assertThat(expResp.updated().getZone().getId()).isEqualTo("America/New_York");
    assertThat(expResp.updated().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 16));
    assertThat(expResp.updated().getHour()).isEqualTo(19);
  }

  @Test
  void expirationsSyncMirrorsAsync() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[1737072000],\"updated\":1705449600}");
    OptionsResource options = resourceWith(client);

    OptionsExpirationsResponse expResp = options.expirations(OptionsExpirationsRequest.of("AAPL"));
    List<ZonedDateTime> exps = expResp.values();
    assertThat(exps).hasSize(1);
  }

  // ---------- expirations: envelope handling ----------

  @Test
  void expirationsNoDataEnvelopeYieldsEmptyList() {
    CapturingClient client = okWith("{\"s\":\"no_data\"}");
    OptionsResource options = resourceWith(client);

    OptionsExpirationsResponse expResp = options.expirations(OptionsExpirationsRequest.of("NOPE"));
    List<ZonedDateTime> exps = expResp.values();
    assertThat(exps).isEmpty();
    assertThat(expResp.updated()).isNull();
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

    OptionsExpirationsResponse expResp = options.expirations(OptionsExpirationsRequest.of("AAPL"));
    List<ZonedDateTime> exps = expResp.values();
    assertThat(exps)
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et));
    assertThat(expResp.updated()).isNotNull();
    assertThat(expResp.updated().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 16));
    assertThat(expResp.updated().getHour()).isEqualTo(19);
    assertThat(expResp.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void expirationsAcceptsSpreadsheetSerialFormat() {
    // Spreadsheet serials are days since 1899-12-30 UTC. 2025-01-17 = 45674.
    ZoneId et = ZoneId.of("America/New_York");
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"expirations\":[45674,45709],\"updated\":45673.79166667}");
    OptionsResource options = resourceWith(client);

    OptionsExpirationsResponse expResp = options.expirations(OptionsExpirationsRequest.of("AAPL"));
    List<ZonedDateTime> exps = expResp.values();
    assertThat(exps)
        .containsExactly(
            LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et),
            LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et));
    assertThat(expResp.updated()).isNotNull();
    assertThat(expResp.updated().getZone().getId()).isEqualTo("America/New_York");
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

  // ---------- strikes: URL & params ----------

  @Test
  void strikesHitsVersionedEndpoint() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":[140,145,150]}");
    OptionsResource options = resourceWith(client);

    options.strikesAsync(OptionsStrikesRequest.of("AAPL")).join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).isEqualTo("http://localhost/v1/options/strikes/AAPL/");
  }

  @Test
  void strikesAttachesExpirationAndDateFilters() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":[140,145]}");
    OptionsResource options = resourceWith(client);

    options
        .strikesAsync(
            OptionsStrikesRequest.builder("AAPL")
                .expiration(LocalDate.of(2025, Month.JANUARY, 17))
                .date(LocalDate.of(2024, Month.DECEMBER, 16))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .isEqualTo(
            "http://localhost/v1/options/strikes/AAPL/?expiration=2025-01-17&date=2024-12-16");
  }

  // ---------- strikes: response decoding ----------

  @Test
  void strikesDecodesMultipleExpirations() {
    ZoneId et = ZoneId.of("America/New_York");
    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"updated\":1705449600,"
                + "\"2025-01-17\":[140.0,145.0,150.0],"
                + "\"2025-02-21\":[135.0,140.0,145.0,150.0]}");
    OptionsResource options = resourceWith(client);

    OptionsStrikesResponse resp = options.strikes(OptionsStrikesRequest.of("AAPL"));
    List<ExpirationStrikes> strikes = resp.values();

    assertThat(strikes).hasSize(2);
    ExpirationStrikes first = strikes.get(0);
    assertThat(first.expiration())
        .isEqualTo(LocalDate.of(2025, Month.JANUARY, 17).atStartOfDay(et));
    assertThat(first.strikes()).containsExactly(140.0, 145.0, 150.0);
    ExpirationStrikes second = strikes.get(1);
    assertThat(second.expiration())
        .isEqualTo(LocalDate.of(2025, Month.FEBRUARY, 21).atStartOfDay(et));
    assertThat(second.strikes()).containsExactly(135.0, 140.0, 145.0, 150.0);
    assertThat(resp.updated()).isNotNull();
    assertThat(resp.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void strikesAcceptsTimestampStringFormatForUpdated() {
    // The expiration keys are ALWAYS literal ISO dates regardless of dateformat (the backend
    // emits str(date) for the key, ignoring the format param). Only `updated` honors dateformat.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"updated\":\"2025-01-16 19:00:00 -05:00\",\"2025-01-17\":[150.0]}");
    OptionsResource options = resourceWith(client);

    OptionsStrikesResponse resp = options.strikes(OptionsStrikesRequest.of("AAPL"));
    List<ExpirationStrikes> strikes = resp.values();
    assertThat(strikes).hasSize(1);
    assertThat(resp.updated()).isNotNull();
    assertThat(resp.updated().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 16));
  }

  // ---------- strikes: envelope handling ----------

  @Test
  void strikesNoDataEnvelopeYieldsEmptyList() {
    // The strikes endpoint also attaches nextTime/prevTime hints in no_data envelopes; the
    // deserializer ignores them (they aren't part of the typed surface).
    CapturingClient client = okWith("{\"s\":\"no_data\",\"nextTime\":null,\"prevTime\":null}");
    OptionsResource options = resourceWith(client);

    OptionsStrikesResponse resp = options.strikes(OptionsStrikesRequest.of("BOGUS"));
    List<ExpirationStrikes> strikes = resp.values();
    assertThat(strikes).isEmpty();
    assertThat(resp.updated()).isNull();
  }

  @Test
  void strikesErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client = okWith("{\"s\":\"error\",\"errmsg\":\"Symbol not found\"}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.strikes(OptionsStrikesRequest.of("BOGUS")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Symbol not found");
  }

  @Test
  void strikesMissingUpdatedThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"2025-01-17\":[150.0]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.strikes(OptionsStrikesRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("updated");
  }

  @Test
  void strikesUnrecognizedTopLevelKeyThrowsParseError() {
    // Strict-by-default — a non-date, non-{s,updated} key signals server change. Surfacing it
    // gives us a diagnostic breadcrumb instead of silently dropping data.
    CapturingClient client = okWith("{\"s\":\"ok\",\"updated\":1705449600,\"surprise\":[1.0]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.strikes(OptionsStrikesRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("unrecognized top-level key: surprise");
  }

  @Test
  void strikesNonNumericStrikeThrowsParseError() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":[\"oops\"]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.strikes(OptionsStrikesRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("non-numeric strike");
  }

  @Test
  void strikesNonArrayExpirationValueThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":150.0}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.strikes(OptionsStrikesRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("non-array value for expiration");
  }

  @Test
  void strikesSyncMirrorsAsync() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":[150.0]}");
    OptionsResource options = resourceWith(client);

    List<ExpirationStrikes> strikes = options.strikes(OptionsStrikesRequest.of("AAPL")).values();
    assertThat(strikes).hasSize(1);
  }

  // ---------- quote (singular): URL & params ----------

  private static final String CANNED_QUOTE_BODY =
      "{\"s\":\"ok\","
          + "\"optionSymbol\":[\"AAPL250117C00150000\"],"
          + "\"underlying\":[\"AAPL\"],"
          + "\"expiration\":[1737136800],"
          + "\"side\":[\"call\"],"
          + "\"strike\":[150],"
          + "\"firstTraded\":[1663118400],"
          + "\"dte\":[45],"
          + "\"updated\":[1705449600],"
          + "\"bid\":[52.1],\"bidSize\":[10],\"mid\":[52.35],\"ask\":[52.6],\"askSize\":[15],"
          + "\"last\":[52.3],\"openInterest\":[5000],\"volume\":[1500],"
          + "\"inTheMoney\":[true],\"intrinsicValue\":[50.22],\"extrinsicValue\":[2.13],"
          + "\"underlyingPrice\":[200.22],"
          + "\"iv\":[0.3012],\"delta\":[0.89],\"gamma\":[0.012],\"theta\":[-0.05],\"vega\":[0.15]}";

  @Test
  void quoteHitsVersionedEndpointWithEncodedSymbol() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    options.quoteAsync(OptionsQuoteRequest.of("AAPL250117C00150000")).join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).isEqualTo("http://localhost/v1/options/quotes/AAPL250117C00150000/");
  }

  @Test
  void quoteAttachesDateFilter() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    options
        .quoteAsync(
            OptionsQuoteRequest.builder("AAPL250117C00150000")
                .date(LocalDate.of(2025, Month.JANUARY, 15))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .isEqualTo("http://localhost/v1/options/quotes/AAPL250117C00150000/?date=2025-01-15");
  }

  @Test
  void quoteAttachesFromToFilters() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    options
        .quoteAsync(
            OptionsQuoteRequest.builder("AAPL250117C00150000")
                .from(LocalDate.of(2024, Month.DECEMBER, 1))
                .to(LocalDate.of(2025, Month.JANUARY, 1))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .isEqualTo(
            "http://localhost/v1/options/quotes/AAPL250117C00150000/"
                + "?from=2024-12-01&to=2025-01-01");
  }

  @Test
  void quoteRequestRejectsDateAndFromToTogether() {
    assertThatThrownBy(
            () ->
                OptionsQuoteRequest.builder("X")
                    .date(LocalDate.of(2025, Month.JANUARY, 1))
                    .from(LocalDate.of(2024, Month.DECEMBER, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void quoteAttachesCountbackWithTo() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    options
        .quoteAsync(
            OptionsQuoteRequest.builder("AAPL250117C00150000")
                .to(LocalDate.of(2025, Month.JANUARY, 1))
                .countback(5)
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .isEqualTo(
            "http://localhost/v1/options/quotes/AAPL250117C00150000/?to=2025-01-01&countback=5");
  }

  @Test
  void quoteRequestRejectsCountbackWithDate() {
    assertThatThrownBy(
            () ->
                OptionsQuoteRequest.builder("X")
                    .date(LocalDate.of(2025, Month.JANUARY, 1))
                    .countback(5)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void quoteRequestRejectsCountbackWithFrom() {
    assertThatThrownBy(
            () ->
                OptionsQuoteRequest.builder("X")
                    .from(LocalDate.of(2024, Month.DECEMBER, 1))
                    .countback(5)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback and from");
  }

  @Test
  void quoteRequestRejectsNonPositiveCountback() {
    assertThatThrownBy(() -> OptionsQuoteRequest.builder("X").countback(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback must be positive");
  }

  @Test
  void quoteRequestRejectsFromAfterTo() {
    assertThatThrownBy(
            () ->
                OptionsQuoteRequest.builder("X")
                    .from(LocalDate.of(2025, Month.JANUARY, 31))
                    .to(LocalDate.of(2025, Month.JANUARY, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must not be after to");
  }

  // ---------- quote: response decoding ----------

  @Test
  void quoteDecodesAllFields() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    List<OptionQuote> response =
        options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).values();

    assertThat(response).hasSize(1);
    OptionQuote q = response.get(0);
    assertThat(q.optionSymbol()).isEqualTo("AAPL250117C00150000");
    assertThat(q.underlying()).isEqualTo("AAPL");
    assertThat(q.side()).isEqualTo("call");
    assertThat(q.strike()).isEqualTo(150.0);
    assertThat(q.dte()).isEqualTo(45);
    assertThat(q.bid()).isEqualTo(52.1);
    assertThat(q.bidSize()).isEqualTo(10L);
    assertThat(q.mid()).isEqualTo(52.35);
    assertThat(q.ask()).isEqualTo(52.6);
    assertThat(q.askSize()).isEqualTo(15L);
    assertThat(q.last()).isEqualTo(52.3);
    assertThat(q.openInterest()).isEqualTo(5000L);
    assertThat(q.volume()).isEqualTo(1500L);
    assertThat(q.inTheMoney()).isTrue();
    assertThat(q.intrinsicValue()).isEqualTo(50.22);
    assertThat(q.extrinsicValue()).isEqualTo(2.13);
    assertThat(q.underlyingPrice()).isEqualTo(200.22);
    assertThat(q.iv()).isEqualTo(0.3012);
    assertThat(q.delta()).isEqualTo(0.89);
    assertThat(q.gamma()).isEqualTo(0.012);
    assertThat(q.theta()).isEqualTo(-0.05);
    assertThat(q.vega()).isEqualTo(0.15);
    // rho is an optional column absent from CANNED_QUOTE_BODY — it must decode to null, not 0.0,
    // and the missing column must not trip the strict parallel-arrays parser.
    assertThat(q.rho()).isNull();
    assertThat(java.util.Objects.requireNonNull(q.expiration()).getZone().getId())
        .isEqualTo("America/New_York");
    assertThat(java.util.Objects.requireNonNull(q.firstTraded()).getZone().getId())
        .isEqualTo("America/New_York");
    assertThat(java.util.Objects.requireNonNull(q.updated()).getZone().getId())
        .isEqualTo("America/New_York");
  }

  @Test
  void quoteDecodesRhoWhenPresent() {
    String bodyWithRho =
        CANNED_QUOTE_BODY.replace("\"vega\":[0.15]}", "\"vega\":[0.15],\"rho\":[0.0456]}");
    CapturingClient client = okWith(bodyWithRho);
    OptionsResource options = resourceWith(client);

    OptionQuote q = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).values().get(0);

    assertThat(q.rho()).isEqualTo(0.0456);
  }

  @Test
  void quoteDecodesNullModelValuesAsNull() {
    // Historical / illiquid rows legitimately carry null iv + greeks (no model output that day).
    // The columns are present, only the cell values are null — they must decode to null rather than
    // tripping the strict parallel-arrays parser. Reproduces the live-API ParseError seen on a
    // countback query for a thinly-traded contract.
    String body =
        CANNED_QUOTE_BODY.replace(
            "\"iv\":[0.3012],\"delta\":[0.89],\"gamma\":[0.012],\"theta\":[-0.05],\"vega\":[0.15]}",
            "\"iv\":[null],\"delta\":[null],\"gamma\":[null],\"theta\":[null],\"vega\":[null]}");
    CapturingClient client = okWith(body);
    OptionsResource options = resourceWith(client);

    OptionQuote q = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).values().get(0);

    assertThat(q.iv()).isNull();
    assertThat(q.delta()).isNull();
    assertThat(q.gamma()).isNull();
    assertThat(q.theta()).isNull();
    assertThat(q.vega()).isNull();
    assertThat(q.rho()).isNull();
    // Market-data fields on the same row stay populated and primitive.
    assertThat(q.bid()).isEqualTo(52.1);
  }

  @Test
  void quoteSyncMirrorsAsync() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    List<OptionQuote> data = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).values();
    assertThat(data).hasSize(1);
  }

  @Test
  void quoteErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client = okWith("{\"s\":\"error\",\"errmsg\":\"Unknown contract\"}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.quote(OptionsQuoteRequest.of("BOGUS")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Unknown contract");
  }

  // ---------- columns projection + Option A + greeks ----------

  @Test
  void columnsProjectionDecodesRequestedAndNullsTheRest() {
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150]}");
    OptionsResource options = resourceWith(client);

    OptionQuote q =
        options
            .columns("optionSymbol", "strike")
            .quote(OptionsQuoteRequest.of("AAPL250117C00150000"))
            .values()
            .get(0);

    assertThat(q.optionSymbol()).isEqualTo("AAPL250117C00150000");
    assertThat(q.strike()).isEqualTo(150.0);
    // Projected away → null, no error.
    assertThat(q.bid()).isNull();
    assertThat(q.volume()).isNull();
    // The columns param reached the wire (comma is URL-encoded, so decode before asserting).
    String decodedUri =
        java.net.URLDecoder.decode(
            client.captured.get(0).uri().toString(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(decodedUri).contains("columns=optionSymbol,strike");
  }

  @Test
  void columnsRequestedButOmittedByApiThrowsParseError() {
    // Option A: asked for bid via columns, but the API didn't return it → loud failure, not a null.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(
            () ->
                options
                    .columns("optionSymbol", "strike", "bid")
                    .quote(OptionsQuoteRequest.of("AAPL250117C00150000")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("bid");
  }

  @Test
  void noColumnsFilterStillRequiresAllStructuralColumns() {
    // With no columns projection every required column is implicitly requested, so a missing one is
    // still a ParseError (the strict-by-default guarantee survives the nullable fields).
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150]}");
    OptionsResource options = resourceWith(client);

    assertThatThrownBy(() -> options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")))
        .isInstanceOf(ParseError.class);
  }

  @Test
  void presentGreeksReportsNonNullGreeks() {
    // CANNED_QUOTE_BODY carries delta/gamma/theta/vega but not rho.
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    OptionQuote q = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).values().get(0);

    assertThat(q.presentGreeks())
        .containsExactlyInAnyOrder(Greek.DELTA, Greek.GAMMA, Greek.THETA, Greek.VEGA);
    assertThat(q.greek(Greek.DELTA)).isEqualTo(0.89);
    assertThat(q.greek(Greek.RHO)).isNull();
  }

  // ---------- CSV / HTML facets ----------

  @Test
  void asCsvSendsFormatCsvAndReturnsRawText() {
    CapturingClient client = okWith("optionSymbol,strike\nAAPL250117C00150000,150");
    OptionsResource options = resourceWith(client);

    CsvResponse csv = options.asCsv().chain(OptionsChainRequest.of("AAPL"));

    assertThat(csv.csv()).contains("optionSymbol,strike");
    assertThat(csv.values()).isEqualTo(csv.csv());
    assertThat(csv.isCsv()).isTrue();
    assertThat(client.captured.get(0).uri().toString()).contains("format=csv");
  }

  @Test
  void asHtmlFacetSendsFormatHtml() {
    // asHtml() is package-private (built, not exposed) — exercised here from the same package.
    CapturingClient client = okWith("<html><body>chain</body></html>");
    OptionsResource options = resourceWith(client);

    HtmlResponse html = options.asHtml().chain(OptionsChainRequest.of("AAPL"));

    assertThat(html.html()).contains("<html>");
    assertThat(client.captured.get(0).uri().toString()).contains("format=html");
  }

  @Test
  void csvFacetUniversalAndShapingParamsReachTheWire() {
    CapturingClient client = okWith("optionSymbol,strike\nAAPL250117C00150000,150");
    OptionsResource options = resourceWith(client);

    options
        .asCsv()
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .columns("optionSymbol", "strike")
        .human(true) // output-shaping — CSV-only
        .headers(true) // output-shaping — CSV-only
        .chain(OptionsChainRequest.of("AAPL"));

    String url =
        java.net.URLDecoder.decode(
            client.captured.get(0).uri().toString(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(url)
        .contains("format=csv")
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10")
        .contains("columns=optionSymbol,strike")
        .contains("human=true")
        .contains("headers=true");
  }

  @Test
  void csvFacetCoversEveryEndpoint() {
    CapturingClient client = okWith("a,b\n1,2");
    OptionsCsvResource csv = resourceWith(client).asCsv();

    assertThat(csv.chain(OptionsChainRequest.of("AAPL")).csv()).contains("a,b");
    assertThat(csv.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).csv()).contains("a,b");
    assertThat(csv.strikes(OptionsStrikesRequest.of("AAPL")).csv()).contains("a,b");
    assertThat(csv.expirations(OptionsExpirationsRequest.of("AAPL")).csv()).contains("a,b");

    Map<String, CsvResponse> fanout =
        csv.quotes(
            OptionsQuotesRequest.builder("AAPL250117C00150000", "AAPL250117P00150000").build());
    assertThat(fanout).hasSize(2);
    assertThat(fanout.values()).allSatisfy(r -> assertThat(r.csv()).contains("a,b"));
  }

  @Test
  void htmlFacetCoversEveryEndpoint() {
    CapturingClient client = okWith("<html>x</html>");
    OptionsHtmlResource html = resourceWith(client).asHtml();

    assertThat(html.chain(OptionsChainRequest.of("AAPL")).html()).contains("<html>");
    assertThat(html.quote(OptionsQuoteRequest.of("AAPL250117C00150000")).html()).contains("<html>");
    assertThat(html.strikes(OptionsStrikesRequest.of("AAPL")).html()).contains("<html>");
    assertThat(html.expirations(OptionsExpirationsRequest.of("AAPL")).html()).contains("<html>");
  }

  // ---------- response metadata (§13.5 / §16) ----------

  @Test
  void responseToStringRedactsQueryString() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    OptionsQuotesResponse resp =
        options.quote(OptionsQuoteRequest.builder("AAPL250117C00150000").countback(5).build());

    // §16: the query string is redacted in toString (logged as /path?…) so params never persist.
    assertThat(resp.toString()).contains("?…").doesNotContain("countback");
  }

  @Test
  void responseSaveToFileWritesRawBodyVerbatim(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    OptionsQuotesResponse resp = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000"));
    java.nio.file.Path out = dir.resolve("quote.json");
    resp.saveToFile(out);

    assertThat(java.nio.file.Files.readString(out))
        .isEqualTo(CANNED_QUOTE_BODY)
        .isEqualTo(resp.json());
  }

  @Test
  void universalParamsReachTheWire() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    options
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .quote(OptionsQuoteRequest.of("AAPL250117C00150000"));

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10");
  }

  @Test
  void responseExposesRequestIdAndUrlMetadata() {
    CapturingClient client = okWith(CANNED_QUOTE_BODY);
    OptionsResource options = resourceWith(client);

    OptionsQuotesResponse resp = options.quote(OptionsQuoteRequest.of("AAPL250117C00150000"));
    assertThat(resp.requestId()).isNull(); // the mock sends no cf-ray header
    assertThat(resp.requestUrl().toString()).contains("/v1/options/quotes/");
  }

  // ---------- quotes (plural, multi-symbol) ----------

  @Test
  void quotesFansOutOnePerSymbolAndPreservesOrder() {
    // Cancellable-but-canned client that responds based on the requested URL — lets us route each
    // fan-out per symbol to a distinct body. Without this, all fan-outs would get the same canned
    // bytes and the test wouldn't observe per-symbol responses.
    SymbolRoutingClient client =
        new SymbolRoutingClient(
            Map.of(
                "AAPL250117C00150000",
                CANNED_QUOTE_BODY,
                "AAPL250117P00150000",
                CANNED_QUOTE_BODY
                    .replace("AAPL250117C00150000", "AAPL250117P00150000")
                    .replace("\"side\":[\"call\"]", "\"side\":[\"put\"]")));

    OptionsResource options = resourceWith(client);

    Map<String, OptionsQuotesResponse> result =
        options.quotes(
            OptionsQuotesRequest.builder("AAPL250117C00150000", "AAPL250117P00150000").build());

    // Insertion order preserved — first symbol in the builder is first in the iteration.
    assertThat(result.keySet()).containsExactly("AAPL250117C00150000", "AAPL250117P00150000");
    assertThat(result.get("AAPL250117C00150000").values().get(0).side()).isEqualTo("call");
    assertThat(result.get("AAPL250117P00150000").values().get(0).side()).isEqualTo("put");

    // Two HTTP requests were sent.
    assertThat(client.captured).hasSize(2);
  }

  @Test
  void quotesAttachesDateFilterToEachFanOut() {
    SymbolRoutingClient client =
        new SymbolRoutingClient(Map.of("X", CANNED_QUOTE_BODY, "Y", CANNED_QUOTE_BODY));
    OptionsResource options = resourceWith(client);

    options.quotes(
        OptionsQuotesRequest.builder("X", "Y").date(LocalDate.of(2025, Month.JANUARY, 1)).build());

    assertThat(client.captured).hasSize(2);
    for (HttpRequest req : client.captured) {
      assertThat(req.uri().toString()).endsWith("?date=2025-01-01");
    }
  }

  @Test
  void quotesAttachesCountbackToEachFanOut() {
    SymbolRoutingClient client =
        new SymbolRoutingClient(Map.of("X", CANNED_QUOTE_BODY, "Y", CANNED_QUOTE_BODY));
    OptionsResource options = resourceWith(client);

    options.quotes(
        OptionsQuotesRequest.builder("X", "Y")
            .to(LocalDate.of(2025, Month.JANUARY, 1))
            .countback(3)
            .build());

    assertThat(client.captured).hasSize(2);
    for (HttpRequest req : client.captured) {
      assertThat(req.uri().toString()).endsWith("?to=2025-01-01&countback=3");
    }
  }

  @Test
  void quotesRequestRequiresAtLeastOneSymbol() {
    // The static factory takes the first symbol non-optionally, so there is no way to construct
    // an empty Builder from public API. The internal Builder constructor is private; this test
    // documents the public-API contract via the static factory.
    assertThatThrownBy(() -> OptionsQuotesRequest.builder(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");
  }

  // ---------- chain: URL & filters ----------

  /** Reuses the canned single-row body from quotes — same wire schema. */
  private static final String CANNED_CHAIN_BODY = CANNED_QUOTE_BODY;

  @Test
  void chainHitsVersionedEndpointWithNoFilters() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options.chainAsync(OptionsChainRequest.of("AAPL")).join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/");
  }

  @Test
  void chainExpirationFilterOnDateTranslatesToExpirationParam() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .expirationFilter(ExpirationFilter.onDate(LocalDate.of(2025, Month.JANUARY, 17)))
                .build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?expiration=2025-01-17");
  }

  @Test
  void chainExpirationFilterDteTranslatesToDteParam() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL").expirationFilter(ExpirationFilter.dte(30)).build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?dte=30");
  }

  @Test
  void chainExpirationFilterBetweenTranslatesToFromTo() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .expirationFilter(
                    ExpirationFilter.between(
                        LocalDate.of(2025, Month.JANUARY, 1), LocalDate.of(2025, Month.MARCH, 31)))
                .build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?from=2025-01-01&to=2025-03-31");
  }

  @Test
  void chainExpirationFilterAllTranslatesToExpirationAll() {
    // expiration=all returns the full chain — distinct from omitting the filter, which the API
    // narrows to the front-month expiration.
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL").expirationFilter(ExpirationFilter.all()).build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?expiration=all");
  }

  @Test
  void chainExpirationFilterMonthYearTranslatesToMonthYear() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .expirationFilter(ExpirationFilter.monthYear(2025, 3))
                .build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?month=3&year=2025");
  }

  @Test
  void chainStrikeFilterExactRendersAsBareNumber() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL").strikeFilter(StrikeFilter.exact(150)).build())
        .join();

    // Integer-valued strikes render without decimal noise so the wire matches the API docs
    // verbatim.
    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?strike=150");
  }

  @Test
  void chainStrikeFilterRangeRendersAsDashed() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL").strikeFilter(StrikeFilter.range(140, 160)).build())
        .join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/options/chain/AAPL/?strike=140-160");
  }

  @Test
  void chainStrikeFilterComparisonRendersWithOperatorPrefix() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .strikeFilter(StrikeFilter.comparison(StrikeFilter.Operator.GTE, 150))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    // "%3E%3D" is the URL-encoded ">=" — the query encoder pushes reserved characters through.
    assertThat(url).contains("strike=%3E%3D150");
  }

  @Test
  void chainBooleanAdditiveFiltersStack() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .weekly(true)
                .monthly(false)
                .quarterly(true)
                .nonstandard(false)
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).contains("weekly=true");
    assertThat(url).contains("monthly=false");
    assertThat(url).contains("quarterly=true");
    assertThat(url).contains("nonstandard=false");
  }

  @Test
  void chainSideEnumTranslatesToWireValue() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options.chainAsync(OptionsChainRequest.builder("AAPL").side(OptionSide.PUT).build()).join();

    assertThat(client.captured.get(0).uri().toString()).endsWith("?side=put");
  }

  @Test
  void chainStrikeLimitAndRangeTranslate() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL").strikeLimit(4).strikeRange(StrikeRange.OTM).build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).contains("strikeLimit=4");
    assertThat(url).contains("range=otm");
  }

  @Test
  void chainLiquidityFiltersTranslate() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    options
        .chainAsync(
            OptionsChainRequest.builder("AAPL")
                .minOpenInterest(500)
                .minVolume(100)
                .maxBidAskSpread(0.5)
                .maxBidAskSpreadPct(0.1)
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).contains("minOpenInterest=500");
    assertThat(url).contains("minVolume=100");
    assertThat(url).contains("maxBidAskSpread=0.5");
    assertThat(url).contains("maxBidAskSpreadPct=0.1");
  }

  // ---------- chain: response decoding ----------

  @Test
  void chainDecodesSingleRowResponse() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    List<OptionQuote> chain = options.chain(OptionsChainRequest.of("AAPL")).values();
    assertThat(chain).hasSize(1);
    OptionQuote q = chain.get(0);
    assertThat(q.optionSymbol()).isEqualTo("AAPL250117C00150000");
    assertThat(q.delta()).isEqualTo(0.89);
  }

  @Test
  void chainSyncMirrorsAsync() {
    CapturingClient client = okWith(CANNED_CHAIN_BODY);
    OptionsResource options = resourceWith(client);

    List<OptionQuote> chain = options.chain(OptionsChainRequest.of("AAPL")).values();
    assertThat(chain).hasSize(1);
  }

  // ---------- chain: builder validation ----------

  @Test
  void chainRequestRejectsMinBidGreaterThanMaxBid() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("AAPL").minBid(10.0).maxBid(5.0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minBid must be <= maxBid");
  }

  @Test
  void chainRequestRejectsMinAskGreaterThanMaxAsk() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("AAPL").minAsk(10.0).maxAsk(5.0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minAsk must be <= maxAsk");
  }

  @Test
  void chainRequestRejectsNegativeStrikeLimit() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("AAPL").strikeLimit(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strikeLimit must be positive");
  }

  @Test
  void chainRequestRejectsNegativeMinOpenInterest() {
    assertThatThrownBy(() -> OptionsChainRequest.builder("AAPL").minOpenInterest(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minOpenInterest must be non-negative");
  }

  // ---------- sealed type factory validation ----------

  @Test
  void expirationFilterDteRejectsNegative() {
    assertThatThrownBy(() -> ExpirationFilter.dte(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dte must be non-negative");
  }

  @Test
  void expirationFilterBetweenRejectsReversedDates() {
    assertThatThrownBy(
            () ->
                ExpirationFilter.between(
                    LocalDate.of(2025, Month.MARCH, 1), LocalDate.of(2025, Month.JANUARY, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be on or before to");
  }

  @Test
  void expirationFilterMonthYearRejectsInvalidMonth() {
    assertThatThrownBy(() -> ExpirationFilter.monthYear(2025, 13))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("month must be in 1..12");
  }

  @Test
  void strikeFilterRangeRejectsMinGreaterThanMax() {
    assertThatThrownBy(() -> StrikeFilter.range(160, 140))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("min must be <= max");
  }

  // ---------- helpers ----------

  private static CapturingClient okWith(String body) {
    return new CapturingClient(200, body.getBytes(), EMPTY_HEADERS);
  }

  /**
   * Test double that routes the request body based on the last non-empty path segment of the URL —
   * lets a single test exercise multi-symbol fan-out where each fan-out should observe a distinct
   * canned response. Falls back to a 200 OK with empty {@code "{}"} for an unmapped path.
   */
  private static final class SymbolRoutingClient extends TestHttpClients.StubHttpClient {
    final List<HttpRequest> captured = new ArrayList<>();
    final Map<String, String> bodies;

    SymbolRoutingClient(Map<String, String> bodies) {
      this.bodies = bodies;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      captured.add(request);
      String[] parts = request.uri().getPath().split("/");
      String tail = "";
      for (int i = parts.length - 1; i >= 0; i--) {
        if (!parts[i].isEmpty()) {
          tail = parts[i];
          break;
        }
      }
      String body = bodies.getOrDefault(tail, "{\"s\":\"no_data\"}");
      HttpResponse<byte[]> resp =
          TestHttpClients.response(
              200, body.getBytes(), EMPTY_HEADERS, URI.create("http://localhost"));
      return (CompletableFuture) CompletableFuture.completedFuture(resp);
    }
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
