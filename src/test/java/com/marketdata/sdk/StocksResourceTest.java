package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.stocks.StockCandle;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarning;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsArticle;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPrice;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
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

class StocksResourceTest {

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
  private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (a, b) -> true);

  private static StocksResource resourceWith(HttpClient client) {
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
    return new StocksResource(transport, new JsonResponseParser());
  }

  // ---------- canned bodies ----------

  private static final String CANDLES_BODY =
      "{\"s\":\"ok\","
          + "\"t\":[1705276800,1705363200],"
          + "\"o\":[216.5,218.0],"
          + "\"h\":[218.55,220.12],"
          + "\"l\":[215.78,217.32],"
          + "\"c\":[217.83,219.68],"
          + "\"v\":[62130000,58240000]}";

  private static final String QUOTE_BODY =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],"
          + "\"ask\":[221.55],\"askSize\":[200],\"bid\":[221.5],\"bidSize\":[300],"
          + "\"mid\":[221.525],\"last\":[221.52],\"change\":[1.38],\"changepct\":[0.0063],"
          + "\"volume\":[58240000],\"updated\":[1705449600]}";

  private static final String QUOTES_BODY =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"MSFT\"],"
          + "\"ask\":[221.55,415.2],\"askSize\":[200,100],\"bid\":[221.5,415.05],"
          + "\"bidSize\":[300,150],\"mid\":[221.525,415.125],\"last\":[221.52,415.1],"
          + "\"change\":[1.38,-2.4],\"changepct\":[0.0063,-0.0057],"
          + "\"volume\":[58240000,22150000],\"updated\":[1705449600,1705449600]}";

  private static final String PRICES_BODY =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"MSFT\"],"
          + "\"mid\":[221.525,415.125],\"change\":[1.38,-2.4],"
          + "\"changepct\":[0.0063,-0.0057],\"updated\":[1705449600,1705449600]}";

  private static final String NEWS_BODY =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"AAPL\"],"
          + "\"headline\":[\"Record Q4\",\"New product\"],"
          + "\"content\":[\"body one\",\"body two\"],"
          + "\"source\":[\"https://a/1\",\"https://b/2\"],"
          + "\"publicationDate\":[1705449600,1705363200],"
          + "\"updated\":1705449600}";

  private static final String EARNINGS_BODY =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],"
          + "\"fiscalYear\":[2024],\"fiscalQuarter\":[3],"
          + "\"date\":[1706659200],\"reportDate\":[1706832000],"
          + "\"reportTime\":[\"after close\"],\"currency\":[\"USD\"],"
          + "\"reportedEPS\":[2.18],\"estimatedEPS\":[2.1],"
          + "\"surpriseEPS\":[0.08],\"surpriseEPSpct\":[3.81],"
          + "\"updated\":[1706832000]}";

  // ---------- candles ----------

  @Test
  void candlesHitsVersionedEndpointWithResolutionAndSymbol() {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    stocks.candlesAsync(StockCandlesRequest.of(StockResolution.DAILY, "AAPL")).join();

    assertThat(client.captured.get(0).uri().toString())
        .isEqualTo("http://localhost/v1/stocks/candles/D/AAPL/");
    assertThat(client.captured.get(0).method()).isEqualTo("GET");
  }

  @Test
  void candlesAttachesAllParams() {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    stocks
        .candlesAsync(
            StockCandlesRequest.builder(StockResolution.hours(1), "AAPL")
                .from(LocalDate.of(2025, Month.JANUARY, 1))
                .to(LocalDate.of(2025, Month.JANUARY, 31))
                .exchange("XNAS")
                .extended(true)
                .country("US")
                .adjustSplits(true)
                .adjustDividends(false)
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("/v1/stocks/candles/1H/AAPL/")
        .contains("from=2025-01-01")
        .contains("to=2025-01-31")
        .contains("exchange=XNAS")
        .contains("extended=true")
        .contains("country=US")
        .contains("adjustsplits=true")
        .contains("adjustdividends=false");
  }

  @Test
  void candlesDecodesOhlcvRows() {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    List<StockCandle> candles =
        stocks.candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL")).values();

    assertThat(candles).hasSize(2);
    StockCandle first = candles.get(0);
    assertThat(first.open()).isEqualTo(216.5);
    assertThat(first.high()).isEqualTo(218.55);
    assertThat(first.low()).isEqualTo(215.78);
    assertThat(first.close()).isEqualTo(217.83);
    assertThat(first.volume()).isEqualTo(62130000L);
    assertThat(first.time()).isNotNull();
    assertThat(first.time().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void candlesAcceptsDateOnlyTimestampStringForDailyBars() {
    // Under dateformat=timestamp the daily candle `t` comes back date-only ("2025-01-17"); the
    // tolerant parser lifts it to a market-zone midnight rather than failing on the missing time.
    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"t\":[\"2025-01-17\"],\"o\":[216.5],\"h\":[218.55],"
                + "\"l\":[215.78],\"c\":[217.83],\"v\":[62130000]}");
    StocksResource stocks = resourceWith(client);

    StockCandle c =
        stocks
            .dateFormat(DateFormat.TIMESTAMP)
            .candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL"))
            .values()
            .get(0);

    assertThat(c.time().toLocalDate()).isEqualTo(LocalDate.of(2025, Month.JANUARY, 17));
    assertThat(c.time().getHour()).isZero();
  }

  @Test
  void candlesNoDataEnvelopeYieldsEmptyList() {
    CapturingClient client = okWith("{\"s\":\"no_data\"}");
    StocksResource stocks = resourceWith(client);

    assertThat(stocks.candles(StockCandlesRequest.of(StockResolution.DAILY, "NOPE")).values())
        .isEmpty();
  }

  @Test
  void candlesErrorEnvelopeSurfacesAsParseError() {
    CapturingClient client = okWith("{\"s\":\"error\",\"errmsg\":\"Invalid resolution\"}");
    StocksResource stocks = resourceWith(client);

    assertThatThrownBy(
            () -> stocks.candles(StockCandlesRequest.of(StockResolution.of("bogus"), "AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("Invalid resolution");
  }

  // ---------- quote (single) ----------

  @Test
  void quoteHitsVersionedEndpointWithFlags() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);

    stocks
        .quoteAsync(
            StockQuoteRequest.builder("AAPL").extended(false).candle(true).week52(true).build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url)
        .contains("/v1/stocks/quotes/AAPL/")
        .contains("extended=false")
        .contains("candle=true")
        .contains("52week=true");
  }

  @Test
  void quoteDecodesRow() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);

    StockQuote q = stocks.quote(StockQuoteRequest.of("AAPL")).values().get(0);

    assertThat(q.symbol()).isEqualTo("AAPL");
    assertThat(q.ask()).isEqualTo(221.55);
    assertThat(q.askSize()).isEqualTo(200L);
    assertThat(q.bid()).isEqualTo(221.5);
    assertThat(q.mid()).isEqualTo(221.525);
    assertThat(q.last()).isEqualTo(221.52);
    assertThat(q.change()).isEqualTo(1.38);
    assertThat(q.changepct()).isEqualTo(0.0063);
    assertThat(q.volume()).isEqualTo(58240000L);
    assertThat(q.updated()).isNotNull();
    assertThat(q.updated().getZone().getId()).isEqualTo("America/New_York");
    // Opt-in columns absent from the body decode to null, not a parse failure.
    assertThat(q.open()).isNull();
    assertThat(q.week52High()).isNull();
  }

  @Test
  void quoteNullNumericCellsDecodeToNull() {
    // Closed/illiquid market: the backend runs NaN→null over numeric columns. The cells are present
    // but null — they must decode to null rather than tripping the strict parser.
    CapturingClient client =
        okWith(
            "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"ask\":[null],\"askSize\":[null],"
                + "\"bid\":[null],\"bidSize\":[null],\"mid\":[null],\"last\":[null],"
                + "\"change\":[null],\"changepct\":[null],\"volume\":[null],\"updated\":[1705449600]}");
    StocksResource stocks = resourceWith(client);

    StockQuote q = stocks.quote(StockQuoteRequest.of("AAPL")).values().get(0);
    assertThat(q.ask()).isNull();
    assertThat(q.volume()).isNull();
    assertThat(q.symbol()).isEqualTo("AAPL");
  }

  @Test
  void quoteSyncMirrorsAsync() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);
    assertThat(stocks.quote(StockQuoteRequest.of("AAPL")).values()).hasSize(1);
  }

  // ---------- quotes (multi, single request) ----------

  @Test
  void quotesBatchesSymbolsInOneRequest() {
    CapturingClient client = okWith(QUOTES_BODY);
    StocksResource stocks = resourceWith(client);

    StockQuotesResponse resp = stocks.quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());

    assertThat(client.captured).hasSize(1);
    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("/v1/stocks/quotes/?").contains("symbols=AAPL,MSFT");
    assertThat(resp.values()).hasSize(2);
    assertThat(resp.values().get(0).symbol()).isEqualTo("AAPL");
    assertThat(resp.values().get(1).symbol()).isEqualTo("MSFT");
  }

  // ---------- prices ----------

  @Test
  void pricesBatchesSymbolsInOneRequest() {
    CapturingClient client = okWith(PRICES_BODY);
    StocksResource stocks = resourceWith(client);

    List<StockPrice> prices = stocks.prices(StockPricesRequest.of("AAPL", "MSFT")).values();

    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("/v1/stocks/prices/?").contains("symbols=AAPL,MSFT");
    assertThat(prices).hasSize(2);
    assertThat(prices.get(0).symbol()).isEqualTo("AAPL");
    assertThat(prices.get(0).mid()).isEqualTo(221.525);
    assertThat(prices.get(0).change()).isEqualTo(1.38);
    assertThat(prices.get(0).updated()).isNotNull();
  }

  // ---------- news ----------

  @Test
  void newsDecodesArticlesAndScalarUpdated() {
    CapturingClient client = okWith(NEWS_BODY);
    StocksResource stocks = resourceWith(client);

    StockNewsResponse resp = stocks.news(StockNewsRequest.of("AAPL"));
    List<StockNewsArticle> articles = resp.values();

    assertThat(client.captured.get(0).uri().toString())
        .startsWith("http://localhost/v1/stocks/news/AAPL/");
    assertThat(articles).hasSize(2);
    assertThat(articles.get(0).headline()).isEqualTo("Record Q4");
    assertThat(articles.get(0).content()).isEqualTo("body one");
    assertThat(articles.get(0).source()).isEqualTo("https://a/1");
    assertThat(articles.get(0).publicationDate().getZone().getId()).isEqualTo("America/New_York");
    // `updated` is a scalar at the response root.
    assertThat(resp.updated()).isNotNull();
    assertThat(resp.updated().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void newsHistoricalQueryOmitsUpdated() {
    // Date-bounded queries omit the scalar `updated`; it must surface as null, not a parse error.
    String body = NEWS_BODY.replace(",\"updated\":1705449600", "");
    CapturingClient client = okWith(body);
    StocksResource stocks = resourceWith(client);

    StockNewsResponse resp =
        stocks.news(
            StockNewsRequest.builder("AAPL")
                .from(LocalDate.of(2024, Month.JANUARY, 1))
                .to(LocalDate.of(2024, Month.FEBRUARY, 1))
                .build());

    assertThat(resp.values()).hasSize(2);
    assertThat(resp.updated()).isNull();
  }

  @Test
  void newsNoDataEnvelopeYieldsEmptyList() {
    CapturingClient client = okWith("{\"s\":\"no_data\"}");
    StocksResource stocks = resourceWith(client);

    StockNewsResponse resp = stocks.news(StockNewsRequest.of("NOPE"));
    assertThat(resp.values()).isEmpty();
    assertThat(resp.updated()).isNull();
  }

  @Test
  void newsRejectsColumnsProjectionOnTypedPath() {
    // StockNewsArticle is non-null by contract, so a typed columns projection can't be honored
    // without lying. It must fail fast and clearly, before any request is dispatched (Option B).
    CapturingClient client = okWith(NEWS_BODY);
    StocksResource stocks = resourceWith(client);

    assertThatThrownBy(() -> stocks.columns("headline").news(StockNewsRequest.of("AAPL")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("news")
        .hasMessageContaining("asCsv");
    // Fail-fast: no request reached the wire.
    assertThat(client.captured).isEmpty();
  }

  @Test
  void newsColumnsRejectionIsAFailedFutureNotASyncThrow() {
    // ADR-006: the async surface signals errors through the future. The guard must NOT throw at the
    // call site (which would bypass .exceptionally/.handle) — newsAsync(...) returns normally and
    // the returned future completes exceptionally instead.
    CapturingClient client = okWith(NEWS_BODY);
    StocksResource stocks = resourceWith(client);

    var future = stocks.columns("headline").newsAsync(StockNewsRequest.of("AAPL"));

    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::join)
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(client.captured).isEmpty();
  }

  @Test
  void newsColumnsProjectionStillWorksOnCsvFacet() {
    // The CSV facet returns raw text — no typed contract to break — so columns stays supported
    // there.
    CapturingClient client = okWith("a,b\n1,2");
    StocksCsvResource csv = resourceWith(client).asCsv();

    assertThat(csv.columns("headline").news(StockNewsRequest.of("AAPL")).csv()).contains("a,b");
    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("columns=headline");
  }

  // ---------- earnings ----------

  @Test
  void earningsDecodesRowWithNullableFundamentals() {
    CapturingClient client = okWith(EARNINGS_BODY);
    StocksResource stocks = resourceWith(client);

    StockEarning e = stocks.earnings(StockEarningsRequest.of("AAPL")).values().get(0);

    assertThat(client.captured.get(0).uri().toString())
        .startsWith("http://localhost/v1/stocks/earnings/AAPL/");
    assertThat(e.symbol()).isEqualTo("AAPL");
    assertThat(e.fiscalYear()).isEqualTo(2024);
    assertThat(e.fiscalQuarter()).isEqualTo(3);
    assertThat(e.reportTime()).isEqualTo("after close");
    assertThat(e.currency()).isEqualTo("USD");
    assertThat(e.reportedEPS()).isEqualTo(2.18);
    assertThat(e.surpriseEPSpct()).isEqualTo(3.81);
    assertThat(e.date()).isNotNull();
    assertThat(e.reportDate()).isNotNull();
    assertThat(e.updated()).isNotNull();
  }

  @Test
  void earningsToleratesNullFutureQuarterFields() {
    // Synthesized forward-quarter row: fundamentals/report fields come back null. (Reproduces a
    // live-API ParseError that strict accessors would have thrown.)
    String body =
        "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],"
            + "\"fiscalYear\":[null],\"fiscalQuarter\":[null],"
            + "\"date\":[1706659200],\"reportDate\":[null],"
            + "\"reportTime\":[null],\"currency\":[\"USD\"],"
            + "\"reportedEPS\":[null],\"estimatedEPS\":[2.1],"
            + "\"surpriseEPS\":[null],\"surpriseEPSpct\":[null],"
            + "\"updated\":[1706832000]}";
    CapturingClient client = okWith(body);
    StocksResource stocks = resourceWith(client);

    StockEarning e = stocks.earnings(StockEarningsRequest.of("AAPL")).values().get(0);
    assertThat(e.fiscalYear()).isNull();
    assertThat(e.fiscalQuarter()).isNull();
    assertThat(e.reportDate()).isNull();
    assertThat(e.reportTime()).isNull();
    assertThat(e.reportedEPS()).isNull();
    assertThat(e.estimatedEPS()).isEqualTo(2.1);
  }

  @Test
  void earningsAttachesWindowAndReportParams() {
    CapturingClient client = okWith(EARNINGS_BODY);
    StocksResource stocks = resourceWith(client);

    stocks
        .earningsAsync(
            StockEarningsRequest.builder("AAPL")
                .to(LocalDate.of(2025, Month.JANUARY, 1))
                .countback(4)
                .report("2024-Q3")
                .build())
        .join();

    String url = client.captured.get(0).uri().toString();
    assertThat(url).contains("to=2025-01-01").contains("countback=4").contains("report=2024-Q3");
  }

  // ---------- columns projection + Option A ----------

  @Test
  void columnsProjectionDecodesRequestedAndNullsTheRest() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}");
    StocksResource stocks = resourceWith(client);

    StockQuote q =
        stocks.columns("symbol", "mid").quote(StockQuoteRequest.of("AAPL")).values().get(0);

    assertThat(q.symbol()).isEqualTo("AAPL");
    assertThat(q.mid()).isEqualTo(221.525);
    assertThat(q.bid()).isNull();
    assertThat(q.volume()).isNull();
    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url).contains("columns=symbol,mid");
  }

  @Test
  void columnsRequestedButOmittedByApiThrowsParseError() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}");
    StocksResource stocks = resourceWith(client);

    assertThatThrownBy(
            () -> stocks.columns("symbol", "mid", "bid").quote(StockQuoteRequest.of("AAPL")))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("bid");
  }

  @Test
  void noColumnsFilterStillRequiresAllStructuralColumns() {
    CapturingClient client = okWith("{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}");
    StocksResource stocks = resourceWith(client);

    assertThatThrownBy(() -> stocks.quote(StockQuoteRequest.of("AAPL")))
        .isInstanceOf(ParseError.class);
  }

  @Test
  void universalParamsReachTheWire() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);

    stocks
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .limit(50)
        .offset(10)
        .quote(StockQuoteRequest.of("AAPL"));

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
    CapturingClient client = okWith("t,o,h,l,c,v\n1705276800,216.5,218.55,215.78,217.83,62130000");
    StocksResource stocks = resourceWith(client);

    CsvResponse csv = stocks.asCsv().candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL"));

    assertThat(csv.csv()).contains("t,o,h,l,c,v");
    assertThat(csv.values()).isEqualTo(csv.csv());
    assertThat(csv.isCsv()).isTrue();
    assertThat(client.captured.get(0).uri().toString()).contains("format=csv");
  }

  @Test
  void csvFacetUniversalAndShapingParamsReachTheWire() {
    CapturingClient client = okWith("a,b\n1,2");
    StocksResource stocks = resourceWith(client);

    stocks
        .asCsv()
        .dateFormat(DateFormat.TIMESTAMP)
        .mode(Mode.DELAYED)
        .columns("symbol", "mid")
        .human(true)
        .headers(true)
        .quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());

    String url = URLDecoder.decode(client.captured.get(0).uri().toString(), StandardCharsets.UTF_8);
    assertThat(url)
        .contains("format=csv")
        .contains("dateformat=timestamp")
        .contains("mode=delayed")
        .contains("columns=symbol,mid")
        .contains("human=true")
        .contains("headers=true");
  }

  @Test
  void csvFacetCoversEveryEndpoint() {
    CapturingClient client = okWith("a,b\n1,2");
    StocksCsvResource csv = resourceWith(client).asCsv();

    assertThat(csv.candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL")).csv())
        .contains("a,b");
    assertThat(csv.quote(StockQuoteRequest.of("AAPL")).csv()).contains("a,b");
    assertThat(csv.quotes(StockQuotesRequest.builder("AAPL", "MSFT").build()).csv())
        .contains("a,b");
    assertThat(csv.prices(StockPricesRequest.of("AAPL")).csv()).contains("a,b");
    assertThat(csv.news(StockNewsRequest.of("AAPL")).csv()).contains("a,b");
    assertThat(csv.earnings(StockEarningsRequest.of("AAPL")).csv()).contains("a,b");
  }

  @Test
  void htmlFacetCoversEveryEndpoint() {
    CapturingClient client = okWith("<html>x</html>");
    StocksHtmlResource html = resourceWith(client).asHtml();

    assertThat(html.candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL")).html())
        .contains("<html>");
    assertThat(html.quote(StockQuoteRequest.of("AAPL")).html()).contains("<html>");
    assertThat(html.quotes(StockQuotesRequest.builder("AAPL", "MSFT").build()).html())
        .contains("<html>");
    assertThat(html.prices(StockPricesRequest.of("AAPL")).html()).contains("<html>");
    assertThat(html.news(StockNewsRequest.of("AAPL")).html()).contains("<html>");
    assertThat(html.earnings(StockEarningsRequest.of("AAPL")).html()).contains("<html>");
    assertThat(client.captured.get(0).uri().toString()).contains("format=html");
  }

  // ---------- response metadata (§13.5 / §16) ----------

  @Test
  void responseToStringRedactsQueryString() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);

    StockQuotesResponse resp = stocks.quote(StockQuoteRequest.builder("AAPL").candle(true).build());

    assertThat(resp.toString()).contains("?…").doesNotContain("candle");
  }

  @Test
  void responseSaveToFileWritesRawBodyVerbatim(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    StockCandlesResponse resp =
        stocks.candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL"));
    java.nio.file.Path out = dir.resolve("candles.json");
    resp.saveToFile(out);

    assertThat(java.nio.file.Files.readString(out)).isEqualTo(CANDLES_BODY).isEqualTo(resp.json());
  }

  // ---------- §12 candle auto-chunking ----------

  @Test
  void candlesIntradayLongRangeSplitsIntoYearChunksAndMerges() {
    // ~3-year intraday range → split into 4 contiguous ≤365-day requests, dispatched concurrently
    // and merged into one response (each canned chunk returns 2 rows → 8 merged).
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    StockCandlesResponse resp =
        stocks.candles(
            StockCandlesRequest.builder(StockResolution.hours(1), "AAPL")
                .from(LocalDate.of(2020, Month.JANUARY, 1))
                .to(LocalDate.of(2023, Month.JANUARY, 1))
                .build());

    assertThat(client.captured).hasSize(4);
    assertThat(resp.values()).hasSize(8);
    // first slice starts at the request's `from`; last slice ends at the request's `to`.
    assertThat(client.captured.get(0).uri().toString()).contains("from=2020-01-01");
    assertThat(client.captured.get(3).uri().toString()).contains("to=2023-01-01");
    // slices are contiguous and non-overlapping: chunk 1's `to` is chunk 2's `from`.
    assertThat(client.captured.get(0).uri().toString()).contains("to=2020-12-31");
    assertThat(client.captured.get(1).uri().toString()).contains("from=2020-12-31");
  }

  @Test
  void candlesDailyLongRangeDoesNotSplit() {
    // Non-intraday resolutions are never chunked, regardless of span.
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    stocks.candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.of(2016, Month.JANUARY, 1))
            .to(LocalDate.of(2024, Month.JANUARY, 1))
            .build());

    assertThat(client.captured).hasSize(1);
  }

  @Test
  void candlesIntradayShortRangeIsASingleRequest() {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    stocks.candles(
        StockCandlesRequest.builder(StockResolution.minutes(15), "AAPL")
            .from(LocalDate.of(2024, Month.JANUARY, 1))
            .to(LocalDate.of(2024, Month.MARCH, 1))
            .build());

    assertThat(client.captured).hasSize(1);
  }

  @Test
  void candlesIntradayWithoutFromIsNotChunked() {
    CapturingClient client = okWith(CANDLES_BODY);
    StocksResource stocks = resourceWith(client);

    stocks.candles(StockCandlesRequest.of(StockResolution.hours(1), "AAPL"));

    assertThat(client.captured).hasSize(1);
    assertThat(client.captured.get(0).uri().toString()).doesNotContain("from=");
  }

  @Test
  void csvFacetCandlesLongRangeMergesChunksAndDedupesHeader() {
    CapturingClient client = okWith("t,o,h,l,c,v\n1705276800,216.5,218.55,215.78,217.83,62130000");
    StocksResource stocks = resourceWith(client);

    CsvResponse csv =
        stocks
            .asCsv()
            .candles(
                StockCandlesRequest.builder(StockResolution.hours(1), "AAPL")
                    .from(LocalDate.of(2020, Month.JANUARY, 1))
                    .to(LocalDate.of(2023, Month.JANUARY, 1))
                    .build());

    assertThat(client.captured).hasSize(4);
    long headerRows = csv.csv().lines().filter(l -> l.startsWith("t,o,h")).count();
    long dataRows = csv.csv().lines().filter(l -> l.startsWith("1705276800")).count();
    assertThat(headerRows).as("header deduped to one").isEqualTo(1);
    assertThat(dataRows).as("one data row per slice").isEqualTo(4);
  }

  @Test
  void mergeCsvBodiesDropsRepeatedHeaderWhenEnabled() {
    List<String> bodies = List.of("h1,h2\n1,2", "h1,h2\n3,4", "h1,h2\n5,6");
    assertThat(StocksCsvResource.mergeCsvBodies(bodies, true)).isEqualTo("h1,h2\n1,2\n3,4\n5,6");
    // headers off → straight concatenation (no line is treated as a header).
    assertThat(StocksCsvResource.mergeCsvBodies(bodies, false))
        .isEqualTo("h1,h2\n1,2\nh1,h2\n3,4\nh1,h2\n5,6");
  }

  @Test
  void resolutionIsIntradayClassifier() {
    assertThat(StockResolution.minutes(15).isIntraday()).isTrue();
    assertThat(StockResolution.hours(1).isIntraday()).isTrue();
    assertThat(StockResolution.of("H").isIntraday()).isTrue();
    assertThat(StockResolution.DAILY.isIntraday()).isFalse();
    assertThat(StockResolution.WEEKLY.isIntraday()).isFalse();
    assertThat(StockResolution.of("1D").isIntraday()).isFalse();
    assertThat(StockResolution.of("daily").isIntraday()).isFalse();
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
        new CapturingClient(200, QUOTE_BODY.getBytes(StandardCharsets.UTF_8), rl);
    StocksResource stocks = resourceWith(client);

    RateLimitSnapshot snap = stocks.quote(StockQuoteRequest.of("AAPL")).rateLimit();
    assertThat(snap).isNotNull();
    assertThat(snap.limit()).isEqualTo(100);
    assertThat(snap.remaining()).isEqualTo(95);
    assertThat(snap.consumed()).isEqualTo(5);
    assertThat(snap.reset()).isNotNull();
  }

  @Test
  void responseRateLimitIsNullWhenHeadersAbsent() {
    CapturingClient client = okWith(QUOTE_BODY);
    StocksResource stocks = resourceWith(client);
    assertThat(stocks.quote(StockQuoteRequest.of("AAPL")).rateLimit()).isNull();
  }

  // ---------- request / resolution validation ----------

  @Test
  void candlesRequestRejectsDateWithRange() {
    assertThatThrownBy(
            () ->
                StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
                    .date(LocalDate.of(2025, Month.JANUARY, 1))
                    .from(LocalDate.of(2024, Month.DECEMBER, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void earningsRequestRejectsCountbackWithFrom() {
    assertThatThrownBy(
            () ->
                StockEarningsRequest.builder("AAPL")
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
                StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
                    .from(LocalDate.of(2025, Month.JANUARY, 31))
                    .to(LocalDate.of(2025, Month.JANUARY, 1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must not be after to");
  }

  @Test
  void quotesRequestRequiresAtLeastOneSymbol() {
    assertThatThrownBy(() -> StockQuotesRequest.builder(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");
  }

  @Test
  void resolutionFactoriesRenderWireTokens() {
    assertThat(StockResolution.minutes(15).wireValue()).isEqualTo("15");
    assertThat(StockResolution.hours(4).wireValue()).isEqualTo("4H");
    assertThat(StockResolution.days(2).wireValue()).isEqualTo("2D");
    assertThat(StockResolution.WEEKLY.wireValue()).isEqualTo("W");
    assertThat(StockResolution.of("3M").wireValue()).isEqualTo("3M");
    assertThatThrownBy(() -> StockResolution.minutes(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  // ---------- helpers ----------

  private static CapturingClient okWith(String body) {
    return new CapturingClient(200, body.getBytes(StandardCharsets.UTF_8), EMPTY_HEADERS);
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
