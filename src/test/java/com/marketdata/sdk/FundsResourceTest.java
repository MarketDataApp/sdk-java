package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class FundsResourceTest {

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
  private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (a, b) -> true);

  private static FundsResource resourceWith(HttpClient client) {
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
    return new FundsResource(transport, new JsonResponseParser());
  }

  // ---------- canned bodies ----------

  // No `v` column: funds are NAV series, the backend never emits volume for them.
  private static final String CANDLES_BODY =
      "{\"s\":\"ok\","
          + "\"t\":[1705276800,1705363200],"
          + "\"o\":[451.21,452.84],"
          + "\"h\":[452.84,454.12],"
          + "\"l\":[450.97,452.1],"
          + "\"c\":[452.84,453.97]}";

  // ---------- candles ----------

  @Test
  void candlesHitsVersionedEndpointWithResolutionAndSymbol() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    funds.candlesAsync(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/funds/candles/D/VFINX/");
    assertThat(client.captured.get(0).method()).isEqualTo("GET");
  }

  @Test
  void candlesAttachesAllParams() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    funds
        .candlesAsync(
            FundCandlesRequest.builder(FundResolution.WEEKLY, "VFINX")
                .from(LocalDate.of(2025, Month.JANUARY, 1))
                .to(LocalDate.of(2025, Month.JANUARY, 31))
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("/v1/funds/candles/W/VFINX/")
        .contains("from=2025-01-01")
        .contains("to=2025-01-31");
  }

  @Test
  void candlesAttachesDateAndCountbackWindows() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    funds.candles(
        FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
            .date(LocalDate.of(2025, Month.JANUARY, 17))
            .build());
    funds.candles(
        FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
            .to(LocalDate.of(2025, Month.JANUARY, 31))
            .countback(20)
            .build());

    assertThat(client.captured.get(0).uri().toString()).contains("date=2025-01-17");
    assertThat(client.captured.get(1).uri().toString())
        .contains("to=2025-01-31")
        .contains("countback=20");
  }

  @Test
  void candlesDecodesOhlcRows() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    List<FundCandle> candles =
        funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).values();

    assertThat(candles).hasSize(2);
    FundCandle first = candles.get(0);
    assertThat(first.open()).isEqualTo(451.21);
    assertThat(first.high()).isEqualTo(452.84);
    assertThat(first.low()).isEqualTo(450.97);
    assertThat(first.close()).isEqualTo(452.84);
    assertThat(first.time()).isNotNull();
    assertThat(first.time().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void candlesAcceptsDateOnlyTimestampStringForDailyBars() {
    // Under dateformat=timestamp the daily candle `t` comes back date-only ("2025-01-17"); the
    // tolerant parser lifts it to a market-zone midnight rather than failing on the missing time.
    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"t\":[\"2025-01-17\"],\"o\":[451.21],\"h\":[452.84],"
                + "\"l\":[450.97],\"c\":[452.84]}");
    FundsResource funds = resourceWith(client);

    FundCandle c =
        funds
            .dateFormat(DateFormat.TIMESTAMP)
            .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"))
            .values()
            .get(0);

    assertThat(c.time().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 17));
    assertThat(c.time().getHour()).isZero();
  }

  @Test
  void candlesNoDataEnvelopeYieldsEmptyList() {
    // The backend signals no_data with 404 + {"s":"no_data"} (the API-wide convention) — the SDK
    // surfaces that as a successful empty response (isNoData() == true), not an exception.
    CapturingClient client = notFoundWith("{\"s\":\"no_data\"}");
    FundsResource funds = resourceWith(client);

    var response = funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "NOPE"));
    assertThat(response.values()).isEmpty();
    assertThat(response.isNoData()).isTrue();
  }

  @Test
  void candlesErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client =
        okWith(
            "{\"s\":\"error\",\"errmsg\":\"Intraday resolutions are not available for fund"
                + " candles.\"}");
    FundsResource funds = resourceWith(client);

    assertThatThrownBy(() -> funds.candles(FundCandlesRequest.of(FundResolution.of("1H"), "VFINX")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Intraday resolutions");
  }

  @Test
  void candlesSyncMirrorsAsync() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);
    assertThat(funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).values())
        .hasSize(2);
  }

  @Test
  void candlesLongDailyRangeIsASingleRequest() {
    // No §12 auto-chunking for funds: there are no intraday resolutions, so even a multi-decade
    // window goes out as one request.
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    funds.candles(
        FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
            .from(LocalDate.of(2000, Month.JANUARY, 1))
            .to(LocalDate.of(2024, Month.JANUARY, 1))
            .build());

    assertThat(client.captured).hasSize(1);
  }

  // ---------- columns projection + Option A ----------

  @Test
  void columnsProjectionDecodesRequestedAndNullsTheRest() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"t\":[1705276800],\"c\":[452.84]}");
    FundsResource funds = resourceWith(client);

    FundCandle c =
        funds
            .columns("t", "c")
            .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"))
            .values()
            .get(0);

    assertThat(c.time()).isNotNull();
    assertThat(c.close()).isEqualTo(452.84);
    assertThat(c.open()).isNull();
    assertThat(c.high()).isNull();
    assertThat(c.low()).isNull();
    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("columns=t,c");
  }

  @Test
  void columnsRequestedButOmittedByApiThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"t\":[1705276800],\"c\":[452.84]}");
    FundsResource funds = resourceWith(client);

    assertThatThrownBy(
            () ->
                funds
                    .columns("t", "c", "o")
                    .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("o");
  }

  @Test
  void noColumnsFilterStillRequiresAllStructuralColumns() {
    // Body is missing `l` with no projection requested — must throw, never silently null.
    CapturingClient client =
        okWith("{\"s\":\"ok\",\"t\":[1705276800],\"o\":[451.21],\"h\":[452.84],\"c\":[452.84]}");
    FundsResource funds = resourceWith(client);

    assertThatThrownBy(() -> funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")))
        .isInstanceOf(ParseError.class);
  }

  @Test
  void universalParamsReachTheWire() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);

    funds
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10");
  }

  // ---------- CSV / HTML facets ----------

  @Test
  void asCsvSendsFormatCsvAndReturnsRawText() {
    CapturingClient client = okWith("t,o,h,l,c\n1705276800,451.21,452.84,450.97,452.84");
    FundsResource funds = resourceWith(client);

    CsvResponse csv = funds.asCsv().candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));

    assertThat(csv.csv()).contains("t,o,h,l,c");
    assertThat(csv.values()).isEqualTo(csv.csv());
    assertThat(csv.isCsv()).isTrue();
    assertThat(client.captured.get(0).uri().toString()).contains("format=csv");
  }

  @Test
  void csvFacetUniversalAndShapingParamsReachTheWire() {
    CapturingClient client = okWith("a,b\n1,2");
    FundsResource funds = resourceWith(client);

    funds
        .asCsv()
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .columns("t", "c")
        .human(true)
        .headers(true)
        .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));

    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url)
        .contains("format=csv")
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("limit=50")
        .contains("offset=10")
        .contains("columns=t,c")
        .contains("human=true")
        .contains("headers=true");
  }

  @Test
  void htmlFacetSendsFormatHtml() {
    CapturingClient client = okWith("<html>x</html>");
    FundsHtmlResource html = resourceWith(client).asHtml();

    assertThat(html.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).html())
        .contains("<html>");
    assertThat(client.captured.get(0).uri().toString()).contains("format=html");
  }

  // ---------- §8.2 per-response rate-limit snapshot ----------

  @Test
  void responseExposesPerResponseRateLimitSnapshot() {
    HttpHeaders rl =
        TestHttpClients.headersOf(
            Map.of(
                "x-api-ratelimit-limit", "100",
                "x-api-ratelimit-remaining", "95",
                "x-api-ratelimit-reset", "1705500000",
                "x-api-ratelimit-consumed", "5"));
    CapturingClient client =
        new CapturingClient(200, CANDLES_BODY.getBytes(StandardCharsets.UTF_8), rl);
    FundsResource funds = resourceWith(client);

    RateLimitSnapshot snap =
        funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).rateLimit();
    assertThat(snap).isNotNull();
    assertThat(snap.limit()).isEqualTo(100);
    assertThat(snap.remaining()).isEqualTo(95);
    assertThat(snap.consumed()).isEqualTo(5);
    assertThat(snap.reset()).isNotNull();
  }

  @Test
  void responseRateLimitIsNullWhenHeadersAbsent() {
    CapturingClient client = okWith(CANDLES_BODY);
    FundsResource funds = resourceWith(client);
    assertThat(funds.candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX")).rateLimit())
        .isNull();
  }

  // ---------- request / resolution validation ----------

  @Test
  void candlesRequestRejectsDateWithRange() {
    assertThatThrownBy(
            () ->
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                    .date(LocalDate.of(2025, Month.JANUARY, 1))
                    .from(LocalDate.of(2024, Month.DECEMBER, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void candlesRequestRejectsCountbackWithFrom() {
    assertThatThrownBy(
            () ->
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                    .from(LocalDate.of(2024, Month.JANUARY, 1))
                    .countback(3)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("countback and from");
  }

  @Test
  void candlesRequestRejectsFromAfterTo() {
    assertThatThrownBy(
            () ->
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                    .from(LocalDate.of(2025, Month.JANUARY, 31))
                    .to(LocalDate.of(2025, Month.JANUARY, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must not be after to");
  }

  @Test
  void candlesRequestRequiresNonEmptySymbol() {
    assertThatThrownBy(() -> FundCandlesRequest.of(FundResolution.DAILY, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");
  }

  @Test
  void resolutionFactoriesRenderWireTokens() {
    assertThat(FundResolution.DAILY.wireValue()).isEqualTo("D");
    assertThat(FundResolution.WEEKLY.wireValue()).isEqualTo("W");
    assertThat(FundResolution.MONTHLY.wireValue()).isEqualTo("M");
    assertThat(FundResolution.YEARLY.wireValue()).isEqualTo("Y");
    assertThat(FundResolution.days(2).wireValue()).isEqualTo("2D");
    assertThat(FundResolution.weeks(3).wireValue()).isEqualTo("3W");
    assertThat(FundResolution.months(6).wireValue()).isEqualTo("6M");
    assertThat(FundResolution.years(1).wireValue()).isEqualTo("1Y");
    assertThat(FundResolution.of("3M").wireValue()).isEqualTo("3M");
    assertThatThrownBy(() -> FundResolution.days(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> FundResolution.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
  }

  // ---------- helpers ----------

  private static CapturingClient okWith(String body) {
    return new CapturingClient(200, body.getBytes(StandardCharsets.UTF_8), EMPTY_HEADERS);
  }

  private static CapturingClient notFoundWith(String body) {
    return new CapturingClient(404, body.getBytes(StandardCharsets.UTF_8), EMPTY_HEADERS);
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
