package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.DateFormat;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Mode;
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
import java.time.LocalDate;
import java.util.List;

/**
 * Exhaustive {@code stocks} resource demo against the mock server. Covers:
 *
 * <ul>
 *   <li>every endpoint (candles, quote, quotes, prices, news, earnings) with its parameter surface —
 *       universal params (dateFormat/mode/limit/offset) + the candle window, the quote opt-in
 *       columns ({@code candle}/{@code 52week}), and the news/earnings date windows;
 *   <li>the CSV facet ({@code asCsv()}) including the output-shaping {@code columns}/{@code
 *       human}/{@code headers} params;
 *   <li>{@code columns} projection: requested fields populate, fields you did <em>not</em> ask for
 *       come back {@code null} with <strong>no error</strong>;
 *   <li>Option A failures: a required column you <em>did</em> request (or didn't project away) that
 *       the API omits raises a {@link ParseError};
 *   <li>§12 candle auto-chunking: an intraday range over a year splits into concurrent sub-requests
 *       and merges into one response;
 *   <li>§8.2 per-response rate limits: {@code rateLimit()} parsed from each response's headers.
 * </ul>
 *
 * <p>Each scenario scripts the mock server's response with {@link MockServerControl#script}.
 *
 * <p>Run: {@code ./gradlew runStocks} (needs the mock server up).
 */
public final class StocksApp {

  private StocksApp() {}

  private static final String CANDLES =
      "{\"s\":\"ok\",\"t\":[1705276800,1705363200],"
          + "\"o\":[216.5,218.0],\"h\":[218.55,220.12],\"l\":[215.78,217.32],"
          + "\"c\":[217.83,219.68],\"v\":[62130000,58240000]}";

  // A single-symbol quote WITH the opt-in candle (o/h/l/c) and 52-week columns present.
  private static final String QUOTE_FULL =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],"
          + "\"ask\":[221.55],\"askSize\":[200],\"bid\":[221.5],\"bidSize\":[300],"
          + "\"mid\":[221.525],\"last\":[221.52],\"change\":[1.38],\"changepct\":[0.0063],"
          + "\"volume\":[58240000],\"updated\":[1705449600],"
          + "\"o\":[219.0],\"h\":[222.3],\"l\":[218.5],\"c\":[221.52],"
          + "\"52weekHigh\":[260.1],\"52weekLow\":[164.08]}";

  private static final String QUOTES =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"MSFT\"],"
          + "\"ask\":[221.55,415.2],\"askSize\":[200,100],\"bid\":[221.5,415.05],"
          + "\"bidSize\":[300,150],\"mid\":[221.525,415.125],\"last\":[221.52,415.1],"
          + "\"change\":[1.38,-2.4],\"changepct\":[0.0063,-0.0057],"
          + "\"volume\":[58240000,22150000],\"updated\":[1705449600,1705449600]}";

  private static final String PRICES =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"MSFT\"],"
          + "\"mid\":[221.525,415.125],\"change\":[1.38,-2.4],"
          + "\"changepct\":[0.0063,-0.0057],\"updated\":[1705449600,1705449600]}";

  private static final String NEWS =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"AAPL\"],"
          + "\"headline\":[\"Apple Reports Record Q4\",\"Apple Announces New Product Line\"],"
          + "\"content\":[\"Apple reported record revenue...\",\"Apple unveiled new products...\"],"
          + "\"source\":[\"https://example.com/a\",\"https://example.com/b\"],"
          + "\"publicationDate\":[1705449600,1705363200],\"updated\":1705449600}";

  // Two rows: a settled historical report, then a synthesized forward quarter whose fundamentals
  // and report fields are null — showing the nullable earnings columns decode cleanly.
  private static final String EARNINGS =
      "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"AAPL\"],"
          + "\"fiscalYear\":[2024,null],\"fiscalQuarter\":[3,null],"
          + "\"date\":[1706659200,1714521600],\"reportDate\":[1706832000,null],"
          + "\"reportTime\":[\"after close\",null],\"currency\":[\"USD\",\"USD\"],"
          + "\"reportedEPS\":[2.18,null],\"estimatedEPS\":[2.1,2.3],"
          + "\"surpriseEPS\":[0.08,null],\"surpriseEPSpct\":[3.81,null],"
          + "\"updated\":[1706832000,1706832000]}";

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      everyEndpointWithParams(mock, client);
      candleAutoChunking(mock, client);
      perResponseRateLimit(mock, client);
      csvFacet(mock, client);
      columnsProjectionDoesNotFail(mock, client);
      optionARequestedColumnMissingFails(mock, client);
      strictByDefaultMissingColumnFails(mock, client);
    }
  }

  // ---------- §12 candle auto-chunking ----------

  private static void candleAutoChunking(MockServerControl mock, MarketDataClient client) {
    Console.header("§12 candle auto-chunking — intraday range > 1 year splits + merges");
    mock.reset();
    // A 3-year HOURLY (intraday) range splits into 4 year-sized sub-requests, fetched concurrently
    // and merged. Script one candle body per slice; each returns 2 rows → 8 merged.
    mock.script(
        List.of(
            Step.of(200, CANDLES), Step.of(200, CANDLES), Step.of(200, CANDLES),
            Step.of(200, CANDLES)));
    Console.step("candles(hours(1), from=2020-01-01, to=2023-01-01) — auto-split");
    var resp =
        client
            .stocks()
            .candles(
                StockCandlesRequest.builder(StockResolution.hours(1), "AAPL")
                    .from(LocalDate.of(2020, 1, 1))
                    .to(LocalDate.of(2023, 1, 1))
                    .build());
    Console.ok(
        "candles.values() → "
            + resp.values().size()
            + " bars merged from "
            + mock.stats().requests()
            + " concurrent sub-requests (one continuous series, transparent to the caller)");
    Console.info("Daily/weekly/… resolutions or no `from` bound → a single request, no chunking.");
  }

  // ---------- §8.2 per-response rate limit ----------

  private static void perResponseRateLimit(MockServerControl mock, MarketDataClient client) {
    Console.header("§8.2 per-response rate limit — rateLimit() off each response");
    mock.reset();
    // Script the four x-api-ratelimit-* headers on the response; the SDK parses them per response.
    mock.script(
        Step.of(200, QUOTE_FULL)
            .withHeader("x-api-ratelimit-limit", "100000")
            .withHeader("x-api-ratelimit-remaining", "99997")
            .withHeader("x-api-ratelimit-reset", "1735689600")
            .withHeader("x-api-ratelimit-consumed", "3"));
    Console.step("quote(\"AAPL\").rateLimit() — parsed from THIS response's headers");
    var resp = client.stocks().quote(StockQuoteRequest.of("AAPL"));
    var rl = resp.rateLimit();
    if (rl != null) {
      Console.ok(
          "rateLimit() → remaining="
              + rl.remaining()
              + "/"
              + rl.limit()
              + " consumed="
              + rl.consumed()
              + " reset="
              + rl.reset());
    } else {
      Console.fail("expected a rate-limit snapshot from the response headers");
    }
    Console.info(
        "Request-scoped — distinct from client.getRateLimits() (the client-wide latest snapshot).");
  }

  // ---------- every endpoint ----------

  private static void everyEndpointWithParams(MockServerControl mock, MarketDataClient client) {
    Console.header("Every stocks endpoint with its parameter surface");

    // candles — OHLCV series. Resolution is a value type; universal params set fluently.
    Console.step("candles(...) — daily OHLCV + universal params (dateFormat/mode/limit)");
    mock.reset();
    mock.script(Step.of(200, CANDLES));
    var candles =
        client
            .stocks()
            .dateFormat(DateFormat.UNIX) // universal param (type-preserving)
            .mode(Mode.DELAYED) // universal param
            .limit(500) // universal param
            .candles(
                StockCandlesRequest.builder(StockResolution.DAILY, "AAPL") // required: resolution + symbol
                    .from(LocalDate.of(2025, 1, 1))
                    .to(LocalDate.of(2025, 1, 31))
                    .adjustSplits(true)
                    .adjustDividends(true)
                    .build());
    List<StockCandle> bars = candles.values(); // List<StockCandle>
    Console.ok("candles.values() → " + bars.size() + " bars; iterating:");
    for (StockCandle bar : bars) {
      Console.info(
          "  " + bar.time() + "  O=" + bar.open() + " H=" + bar.high() + " L=" + bar.low()
              + " C=" + bar.close() + " V=" + bar.volume());
    }

    // quote — single symbol, opt-in candle + 52-week columns.
    Console.step("quote(...) — single symbol with candle=true & 52week=true opt-in columns");
    mock.reset();
    mock.script(Step.of(200, QUOTE_FULL));
    StockQuote q =
        client
            .stocks()
            .quote(StockQuoteRequest.builder("AAPL").candle(true).week52(true).build())
            .values()
            .get(0);
    Console.ok(
        "quote → " + q.symbol() + "  bid/ask=" + q.bid() + "/" + q.ask() + "  last=" + q.last());
    Console.info(
        "  opt-in candle: O=" + q.open() + " H=" + q.high() + " L=" + q.low() + " C=" + q.close());
    Console.info("  opt-in 52week: high=" + q.week52High() + " low=" + q.week52Low());

    // quotes — multi-symbol BATCH in ONE request (comma list) → one response with N rows.
    Console.step("quotes(...) — multi-symbol batch (single request, one response with N rows)");
    mock.reset();
    mock.script(Step.of(200, QUOTES));
    var quotes = client.stocks().quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());
    Console.ok("quotes.values() → " + quotes.values().size() + " rows (single request):");
    for (StockQuote row : quotes.values()) {
      Console.info("  " + row.symbol() + "  mid=" + row.mid() + "  vol=" + row.volume());
    }

    // prices — lighter than quotes (mid/change/updated), also a single batched request.
    Console.step("prices(...) — light multi-symbol price snapshot (mid/change)");
    mock.reset();
    mock.script(Step.of(200, PRICES));
    var prices = client.stocks().prices(StockPricesRequest.of("AAPL", "MSFT"));
    for (StockPrice p : prices.values()) {
      Console.info("  " + p.symbol() + "  mid=" + p.mid() + "  change=" + p.change());
    }

    // news — per-row articles + a scalar `updated` exposed off the response (not on each row).
    Console.step("news(...) — articles + scalar updated() on the response");
    mock.reset();
    mock.script(Step.of(200, NEWS));
    var news = client.stocks().news(StockNewsRequest.of("AAPL"));
    Console.ok("news.values() → " + news.values().size() + " articles; updated=" + news.updated());
    for (StockNewsArticle a : news.values()) {
      Console.info("  " + a.publicationDate().toLocalDate() + "  " + a.headline());
    }

    // earnings — history + forward calendar; nullable fundamentals/report fields decode to null.
    Console.step("earnings(...) — history + forward quarter (nullable fields decode cleanly)");
    mock.reset();
    mock.script(Step.of(200, EARNINGS));
    var earnings =
        client
            .stocks()
            .earnings(
                StockEarningsRequest.builder("AAPL")
                    .to(LocalDate.of(2025, 6, 1))
                    .countback(8)
                    .build());
    for (StockEarning e : earnings.values()) {
      Console.info(
          "  FY"
              + e.fiscalYear()
              + " Q"
              + e.fiscalQuarter()
              + "  reportedEPS="
              + e.reportedEPS()
              + " estimatedEPS="
              + e.estimatedEPS()
              + " reportTime="
              + e.reportTime());
    }
  }

  // ---------- CSV facet ----------

  private static void csvFacet(MockServerControl mock, MarketDataClient client) {
    Console.header("CSV facet — client.stocks().asCsv()");

    Console.step("asCsv().candles(...) — plain CSV");
    mock.reset();
    mock.script(
        Step.of(200, "t,o,h,l,c,v\n1705276800,216.5,218.55,215.78,217.83,62130000"));
    var csv =
        client.stocks().asCsv().candles(StockCandlesRequest.of(StockResolution.DAILY, "AAPL"));
    Console.ok("→ CsvResponse (" + csv.csv().length() + " chars):");
    Console.info(csv.csv());

    // columns / human / headers reshape the output, so they live ONLY on the CSV facet.
    Console.step("asCsv().columns(...).human(true).headers(true) — output-shaping params (CSV-only)");
    mock.reset();
    mock.script(Step.of(200, "Symbol,Mid Price\nAAPL,221.525\nMSFT,415.125"));
    var shaped =
        client
            .stocks()
            .asCsv()
            .columns("symbol", "mid")
            .human(true)
            .headers(true)
            .quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());
    Console.ok("→ CSV with human headers + projected columns:");
    Console.info(shaped.csv());
  }

  // ---------- columns projection: no failure when a non-requested field is absent ----------

  private static void columnsProjectionDoesNotFail(MockServerControl mock, MarketDataClient client) {
    Console.header("columns projection — non-requested fields come back null, NO error");
    mock.reset();
    // The mock returns ONLY the projected columns (as the real API would for ?columns=...).
    mock.script(Step.of(200, "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}"));

    StockQuote row =
        client.stocks().columns("symbol", "mid").quote(StockQuoteRequest.of("AAPL")).values().get(0);
    Console.ok("requested → symbol=" + row.symbol() + " mid=" + row.mid());
    Console.ok(
        "NOT requested (null, decoded cleanly) → bid="
            + row.bid()
            + " volume="
            + row.volume()
            + " updated="
            + row.updated());
  }

  // ---------- Option A: requested column missing → ParseError ----------

  private static void optionARequestedColumnMissingFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Option A — requested a column the API omitted → ParseError");
    mock.reset();
    // Consumer asks for bid, but the body omits it → anomaly, not a projection.
    mock.script(Step.of(200, "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}"));

    try {
      client.stocks().columns("symbol", "mid", "bid").quote(StockQuoteRequest.of("AAPL"));
      Console.fail("expected a ParseError — 'bid' was requested but the API did not return it");
    } catch (ParseError e) {
      Console.ok("ParseError as expected: " + e.getMessage());
    }
  }

  // ---------- strict by default: no columns filter still requires all structural columns ----------

  private static void strictByDefaultMissingColumnFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Strict by default — no columns filter, but a required column is missing");
    mock.reset();
    mock.script(Step.of(200, "{\"s\":\"ok\",\"symbol\":[\"AAPL\"],\"mid\":[221.525]}"));

    try {
      // No .columns(...) → every required column is implicitly requested, so a missing one fails.
      client.stocks().quote(StockQuoteRequest.of("AAPL"));
      Console.fail("expected a ParseError — required columns are missing and none were projected away");
    } catch (ParseError e) {
      Console.ok(
          "ParseError as expected (nullable fields did NOT weaken the strict default): "
              + e.getMessage());
    }
  }
}
