package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.DateFormat;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Mode;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.options.ExpirationFilter;
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
import java.time.LocalDate;
import java.util.List;

/**
 * Exhaustive {@code options} resource demo against the mock server. Covers:
 *
 * <ul>
 *   <li>every endpoint (lookup, expirations, strikes, quote, quotes, chain) with the full
 *       parameter surface — universal params (dateFormat/mode/limit/offset) + the rich chain
 *       filters (sealed expiration/strike groups, side, liquidity/price filters, …);
 *   <li>the CSV facet ({@code asCsv()});
 *   <li>{@code columns} projection: requested fields populate, fields you did <em>not</em> ask for
 *       come back {@code null} with <strong>no error</strong>;
 *   <li>Option A failures: a required column you <em>did</em> request (or didn't project away) that
 *       the API omits raises a {@link ParseError}.
 * </ul>
 *
 * <p>Each scenario scripts the mock server's response with {@link MockServerControl#script}, so the
 * "API" returns exactly the body the scenario needs.
 *
 * <p>Run: {@code ./gradlew runOptions} (needs the mock server up).
 */
public final class OptionsApp {

  private OptionsApp() {}

  // A full option-quote row (every column present) — used by chain/quote.
  private static final String FULL_ROW =
      "{\"s\":\"ok\","
          + "\"optionSymbol\":[\"AAPL250117C00150000\"],\"underlying\":[\"AAPL\"],"
          + "\"expiration\":[1737136800],\"side\":[\"call\"],\"strike\":[150],"
          + "\"firstTraded\":[1663118400],\"dte\":[45],\"updated\":[1705449600],"
          + "\"bid\":[12.55],\"bidSize\":[10],\"mid\":[12.7],\"ask\":[12.85],\"askSize\":[8],"
          + "\"last\":[12.8],\"openInterest\":[15234],\"volume\":[289],\"inTheMoney\":[true],"
          + "\"intrinsicValue\":[3.38],\"extrinsicValue\":[9.32],\"underlyingPrice\":[153.38],"
          + "\"iv\":[0.2432],\"delta\":[0.5862],\"gamma\":[0.015],\"theta\":[-0.1347],"
          + "\"vega\":[0.4152],\"rho\":[0.0891]}";

  // A chain with three contracts — so iterating values() shows more than one row.
  private static final String THREE_ROWS =
      "{\"s\":\"ok\","
          + "\"optionSymbol\":[\"AAPL250117C00150000\",\"AAPL250117C00155000\",\"AAPL250117C00160000\"],"
          + "\"underlying\":[\"AAPL\",\"AAPL\",\"AAPL\"],"
          + "\"expiration\":[1737136800,1737136800,1737136800],"
          + "\"side\":[\"call\",\"call\",\"call\"],"
          + "\"strike\":[150,155,160],"
          + "\"firstTraded\":[1663118400,1663118400,1663118400],"
          + "\"dte\":[45,45,45],\"updated\":[1705449600,1705449600,1705449600],"
          + "\"bid\":[12.55,8.90,6.10],\"bidSize\":[10,12,8],\"mid\":[12.7,9.0,6.2],"
          + "\"ask\":[12.85,9.10,6.30],\"askSize\":[8,9,7],\"last\":[12.8,9.0,6.2],"
          + "\"openInterest\":[15234,9921,7044],\"volume\":[289,144,98],"
          + "\"inTheMoney\":[true,false,false],\"intrinsicValue\":[3.38,0,0],"
          + "\"extrinsicValue\":[9.32,9.0,6.2],\"underlyingPrice\":[153.38,153.38,153.38],"
          + "\"iv\":[0.2432,0.2401,0.2380],\"delta\":[0.5862,0.5096,0.4401],"
          + "\"gamma\":[0.015,0.0155,0.0150],\"theta\":[-0.1347,-0.1343,-0.1320],"
          + "\"vega\":[0.4152,0.4251,0.4180],\"rho\":[0.0891,0.0810,0.0732]}";

  private static final String EXPIRATIONS =
      "{\"s\":\"ok\",\"expirations\":[1737072000,1739491200],\"updated\":1705449600}";
  private static final String STRIKES =
      "{\"s\":\"ok\",\"updated\":1705449600,\"2025-01-17\":[145.0,150.0,155.0]}";
  private static final String LOOKUP = "{\"s\":\"ok\",\"optionSymbol\":\"AAPL250117C00150000\"}";

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      everyEndpointWithAllParams(mock, client);
      csvFacet(mock, client);
      columnsProjectionDoesNotFail(mock, client);
      optionARequestedColumnMissingFails(mock, client);
      strictByDefaultMissingColumnFails(mock, client);
    }
  }

  // ---------- every endpoint, all params ----------

  private static void everyEndpointWithAllParams(MockServerControl mock, MarketDataClient client) {
    Console.header("Every options endpoint with the full parameter surface");

    // chain — the richest filter surface, plus universal params set fluently on the resource.
    Console.step("chain(...) — sealed filters + side/liquidity/price filters + universal params");
    mock.reset();
    mock.script(Step.of(200, THREE_ROWS));
    var chain =
        client
            .options()
            .dateFormat(DateFormat.TIMESTAMP) // universal param (type-preserving)
            .mode(Mode.DELAYED) // universal param
            .limit(50) // universal param
            .offset(0) // universal param
            .chain(
                OptionsChainRequest.builder("AAPL") // required: underlying
                    .expirationFilter(ExpirationFilter.dte(45)) // sealed mutex: pick ONE
                    .strikeFilter(StrikeFilter.range(150, 250)) // sealed mutex: pick ONE
                    .strikeRange(StrikeRange.ITM)
                    .side(OptionSide.CALL)
                    .strikeLimit(10) // (strikeLimit/delta are alternative strike selectors)
                    .delta(0.5)
                    .weekly(true)
                    .monthly(true)
                    .quarterly(true)
                    .am(true)
                    .pm(true)
                    .nonstandard(true)
                    .minBid(0.1)
                    .maxBid(100.0)
                    .minAsk(0.1)
                    .maxAsk(100.0)
                    .maxBidAskSpread(5.0)
                    .maxBidAskSpreadPct(50.0)
                    .minOpenInterest(100)
                    .minVolume(10)
                    .date(LocalDate.of(2025, 1, 2)) // historical snapshot
                    .build());
    // .values() returns a List<OptionQuote> — iterate it row by row.
    List<OptionQuote> rows = chain.values();
    Console.ok("chain.values() → List<OptionQuote> with " + rows.size() + " contracts; iterating:");
    for (OptionQuote row : rows) {
      Console.info(
          "  "
              + row.optionSymbol()
              + "  strike="
              + row.strike()
              + "  bid/ask="
              + row.bid()
              + "/"
              + row.ask()
              + "  inTheMoney="
              + row.inTheMoney()
              + "  delta="
              + row.delta()
              + "  greeks="
              + row.presentGreeks());
    }
    Console.info("statusCode=" + chain.statusCode() + " isNoData=" + chain.isNoData());

    // quote — single OCC symbol, date-window params.
    Console.step("quote(...) — single OCC symbol + date window");
    mock.reset();
    mock.script(Step.of(200, FULL_ROW));
    var quote =
        client
            .options()
            .dateFormat(DateFormat.UNIX)
            .quote(
                OptionsQuoteRequest.builder("AAPL250117C00150000")
                    .from(LocalDate.of(2025, 1, 1)) // from+to = a date range (date window is mutex)
                    .to(LocalDate.of(2025, 1, 10))
                    .build());
    List<OptionQuote> quoteRows = quote.values(); // also a List<OptionQuote>
    Console.ok("quote.values() → List<OptionQuote> with " + quoteRows.size() + " row(s); iterating:");
    for (OptionQuote row : quoteRows) {
      Console.info("  " + row.optionSymbol() + "  last=" + row.last() + "  iv=" + row.iv());
    }

    // quotes — fan-out over several OCC symbols → one request per symbol → per-symbol map.
    Console.step("quotes(...) — multi OCC symbol fan-out (Map<String, response>)");
    mock.reset();
    mock.script(List.of(Step.of(200, FULL_ROW), Step.of(200, FULL_ROW)));
    var quotes =
        client
            .options()
            .quotes(
                OptionsQuotesRequest.builder("AAPL250117C00150000", "AAPL250117P00150000").build());
    quotes.forEach((sym, resp) -> Console.info("  " + sym + " → " + resp.values().size() + " row(s)"));

    // strikes — underlying + optional expiration/date; response is per-expiration strike lists.
    Console.step("strikes(...) — strike ladder per expiration");
    mock.reset();
    mock.script(Step.of(200, STRIKES));
    var strikes =
        client
            .options()
            .strikes(
                OptionsStrikesRequest.builder("AAPL")
                    .expiration(LocalDate.of(2025, 1, 17))
                    .date(LocalDate.of(2025, 1, 2))
                    .build());
    Console.ok(
        "strikes → "
            + strikes.values().size()
            + " expiration(s), updated="
            + strikes.updated());

    // expirations — underlying + optional strike/date; response is a list of dates.
    Console.step("expirations(...) — available expiration dates");
    mock.reset();
    mock.script(Step.of(200, EXPIRATIONS));
    var exps =
        client
            .options()
            .expirations(
                OptionsExpirationsRequest.builder("AAPL")
                    .strike(150.0)
                    .date(LocalDate.of(2025, 1, 2))
                    .build());
    Console.ok("expirations → " + exps.values().size() + " date(s), updated=" + exps.updated());

    // lookup — free-text → OCC symbol (scalar; no universal params, no facet).
    Console.step("lookup(...) — human description → OCC symbol");
    mock.reset();
    mock.script(Step.of(200, LOOKUP));
    var sym = client.options().lookup(OptionsLookupRequest.of("AAPL 1/17/25 $150 call"));
    Console.ok("lookup → " + sym.values());
  }

  // ---------- CSV facet ----------

  private static void csvFacet(MockServerControl mock, MarketDataClient client) {
    Console.header("CSV facet — client.options().asCsv()");

    Console.step("asCsv().chain(...) — plain CSV");
    mock.reset();
    mock.script(Step.of(200, "optionSymbol,strike,bid\nAAPL250117C00150000,150,12.55"));
    var csv = client
            .options()
            .asCsv()
            .chain(
                    OptionsChainRequest.of("AAPL")
            );
    Console.ok("→ CsvResponse (" + csv.csv().length() + " chars):");
    Console.info(csv.csv());

    // columns / human / headers reshape the output, so they live ONLY on the CSV facet.
    Console.step("asCsv().columns(...).human(true).headers(true) — output-shaping params (CSV-only)");
    mock.reset();
    mock.script(Step.of(200, "Option Symbol,Strike Price,Bid\nAAPL250117C00150000,150,12.55"));
    var shaped =
        client
            .options()
            .asCsv()
            .columns("optionSymbol", "strike", "bid") // project to a subset
            .human(true) // human-readable column names
            .headers(true) // include the header row
            .chain(OptionsChainRequest.of("AAPL"));
    Console.ok("→ CSV with human headers + projected columns:");
    Console.info(shaped.csv());

    // Fan-out in CSV mirrors the typed map: one CsvResponse per symbol.
    Console.step("asCsv().quotes(...) — fan-out → Map<String, CsvResponse>");
    mock.reset();
    mock.script(
        List.of(
            Step.of(200, "optionSymbol,bid\nAAPL250117C00150000,12.55"),
            Step.of(200, "optionSymbol,bid\nAAPL250117P00150000,3.10")));
    var csvMap =
        client
            .options()
            .asCsv()
            .quotes(
                OptionsQuotesRequest.builder("AAPL250117C00150000", "AAPL250117P00150000").build());
    csvMap.forEach(
        (sym, resp) -> Console.info("  " + sym + " → " + resp.csv().replace("\n", " ⏎ ")));
  }

  // ---------- columns projection: no failure when a non-requested field is absent ----------

  private static void columnsProjectionDoesNotFail(MockServerControl mock, MarketDataClient client) {
    Console.header("columns projection — non-requested fields come back null, NO error");
    mock.reset();
    // The mock returns ONLY the projected columns (as the real API would for ?columns=...).
    mock.script(
        Step.of(
            200,
            "{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150],"
                + "\"delta\":[0.5862]}"));

    var proj =
        client
            .options()
            .columns("optionSymbol", "strike", "delta")
            .chain(OptionsChainRequest.of("AAPL"));

    List<OptionQuote> rows = proj.values(); // still a List<OptionQuote>, just with most fields null
    for (OptionQuote row : rows) {
      Console.ok(
          "requested → optionSymbol="
              + row.optionSymbol()
              + " strike="
              + row.strike()
              + " delta="
              + row.delta());
      Console.ok(
          "NOT requested (null, decoded cleanly) → bid="
              + row.bid()
              + " volume="
              + row.volume()
              + " iv="
              + row.iv());
    }
  }

  // ---------- Option A: requested column missing → ParseError ----------

  private static void optionARequestedColumnMissingFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Option A — requested a column the API omitted → ParseError");
    mock.reset();
    // Consumer asks for bid, but the body omits it → anomaly, not a projection.
    mock.script(
        Step.of(200, "{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150]}"));

    try {
      client
          .options()
          .columns("optionSymbol", "strike", "bid")
          .chain(OptionsChainRequest.of("AAPL"));
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
    mock.script(
        Step.of(200, "{\"s\":\"ok\",\"optionSymbol\":[\"AAPL250117C00150000\"],\"strike\":[150]}"));

    try {
      // No .columns(...) → every required column is implicitly requested, so a missing one fails.
      client.options().chain(OptionsChainRequest.of("AAPL"));
      Console.fail("expected a ParseError — required columns are missing and none were projected away");
    } catch (ParseError e) {
      Console.ok("ParseError as expected (nullable fields did NOT weaken the strict default): " + e.getMessage());
    }
  }
}
