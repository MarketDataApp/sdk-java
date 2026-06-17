package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.DateFormat;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Mode;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import java.time.LocalDate;
import java.util.List;

/**
 * Exhaustive {@code funds} resource demo against the mock server. Covers:
 *
 * <ul>
 *   <li>the single endpoint ({@code candles}) with its full parameter surface — universal params
 *       (dateFormat/mode/limit/offset) + the three window shapes ({@code from}/{@code to}, {@code
 *       date}, {@code to}+{@code countback});
 *   <li>what funds do NOT have: no volume column (NAV series), no intraday resolutions (the API
 *       rejects them), and therefore no §12 auto-chunking — a multi-decade daily range is one
 *       request;
 *   <li>the CSV facet ({@code asCsv()}) including the output-shaping {@code columns}/{@code
 *       human}/{@code headers} params;
 *   <li>{@code columns} projection: requested fields populate, fields you did <em>not</em> ask for
 *       come back {@code null} with <strong>no error</strong>;
 *   <li>Option A failures: a required column you <em>did</em> request (or didn't project away) that
 *       the API omits raises a {@link ParseError};
 *   <li>§8.2 per-response rate limits: {@code rateLimit()} parsed from each response's headers.
 * </ul>
 *
 * <p>Each scenario scripts the mock server's response with {@link MockServerControl#script}.
 *
 * <p>Run: {@code ./gradlew runFunds} (needs the mock server up).
 */
public final class FundsApp {

  private FundsApp() {}

  // OHLC only — funds are NAV series, the backend never emits a volume column for them.
  private static final String CANDLES =
      "{\"s\":\"ok\",\"t\":[1705276800,1705363200],"
          + "\"o\":[451.21,452.84],\"h\":[452.84,454.12],\"l\":[450.97,452.1],"
          + "\"c\":[452.84,453.97]}";

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      candlesWithParams(mock, client);
      noIntradayNoChunking(mock, client);
      perResponseRateLimit(mock, client);
      csvFacet(mock, client);
      columnsProjectionDoesNotFail(mock, client);
      optionARequestedColumnMissingFails(mock, client);
      strictByDefaultMissingColumnFails(mock, client);
    }
  }

  // ---------- the candles endpoint, all parameter shapes ----------

  private static void candlesWithParams(MockServerControl mock, MarketDataClient client) {
    Console.header("funds.candles — the parameter surface");

    // from/to window + universal params, set fluently.
    Console.step("candles(...) — daily OHLC + universal params (dateFormat/mode/limit)");
    mock.reset();
    mock.script(Step.of(200, CANDLES));
    var candles =
        client
            .funds()
            .dateFormat(DateFormat.UNIX) // universal param (type-preserving)
            .mode(Mode.DELAYED) // universal param
            .limit(500) // universal param
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX") // required: resolution + symbol
                    .from(LocalDate.of(2025, 1, 1))
                    .to(LocalDate.of(2025, 1, 31))
                    .build());
    List<FundCandle> bars = candles.values(); // List<FundCandle>
    Console.ok("candles.values() → " + bars.size() + " bars; iterating (note: no volume column):");
    for (FundCandle bar : bars) {
      Console.info(
          "  " + bar.time() + "  O=" + bar.open() + " H=" + bar.high() + " L=" + bar.low()
              + " C=" + bar.close());
    }

    // Single-day lookup: date is mutually exclusive with from/to/countback.
    Console.step("candles(date=...) — single trading day");
    mock.reset();
    mock.script(Step.of(200, CANDLES));
    var single =
        client
            .funds()
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                    .date(LocalDate.of(2025, 1, 17))
                    .build());
    Console.ok("candles.values() → " + single.values().size() + " bars for one session");

    // to + countback: "the last N candles before `to`" — no left edge needed.
    Console.step("candles(to=..., countback=20) — last 20 sessions, weekly resolution");
    mock.reset();
    mock.script(Step.of(200, CANDLES));
    var counted =
        client
            .funds()
            .candles(
                FundCandlesRequest.builder(FundResolution.WEEKLY, "VFINX")
                    .to(LocalDate.of(2025, 1, 31))
                    .countback(20)
                    .build());
    Console.ok("candles.values() → " + counted.values().size() + " weekly bars");
  }

  // ---------- funds-specific: no intraday → no §12 chunking ----------

  private static void noIntradayNoChunking(MockServerControl mock, MarketDataClient client) {
    Console.header("No intraday for funds — and therefore no §12 auto-chunking");
    mock.reset();
    mock.script(Step.of(200, CANDLES));
    Console.step("candles(DAILY, from=2000-01-01, to=2024-01-01) — 24 years, ONE request");
    var resp =
        client
            .funds()
            .candles(
                FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                    .from(LocalDate.of(2000, 1, 1))
                    .to(LocalDate.of(2024, 1, 1))
                    .build());
    Console.ok(
        "candles.values() → "
            + resp.values().size()
            + " bars from "
            + mock.stats().requests()
            + " request (the year-span split only applies to intraday candles, which funds"
            + " don't serve)");
    Console.info(
        "FundResolution has no minutes()/hours() factories; the API rejects intraday tokens with"
            + " \"Intraday resolutions are not available for fund candles.\"");
  }

  // ---------- §8.2 per-response rate limit ----------

  private static void perResponseRateLimit(MockServerControl mock, MarketDataClient client) {
    Console.header("§8.2 per-response rate limit — rateLimit() off each response");
    mock.reset();
    // Script the four x-api-ratelimit-* headers on the response; the SDK parses them per response.
    mock.script(
        Step.of(200, CANDLES)
            .withHeader("x-api-ratelimit-limit", "100000")
            .withHeader("x-api-ratelimit-remaining", "99997")
            .withHeader("x-api-ratelimit-reset", "1735689600")
            .withHeader("x-api-ratelimit-consumed", "3"));
    Console.step("candles(...).rateLimit() — parsed from THIS response's headers");
    var resp = client.funds().candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));
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

  // ---------- CSV facet ----------

  private static void csvFacet(MockServerControl mock, MarketDataClient client) {
    Console.header("CSV facet — client.funds().asCsv()");

    Console.step("asCsv().candles(...) — plain CSV");
    mock.reset();
    mock.script(Step.of(200, "t,o,h,l,c\n1705276800,451.21,452.84,450.97,452.84"));
    var csv = client.funds().asCsv().candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));
    Console.ok("→ CsvResponse (" + csv.csv().length() + " chars):");
    Console.info(csv.csv());

    // columns / human / headers reshape the output, so they live ONLY on the CSV facet.
    Console.step("asCsv().columns(...).human(true).headers(true) — output-shaping params (CSV-only)");
    mock.reset();
    mock.script(Step.of(200, "Date,Close\n2025-01-15,452.84\n2025-01-16,453.97"));
    var shaped =
        client
            .funds()
            .asCsv()
            .columns("t", "c")
            .human(true)
            .headers(true)
            .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));
    Console.ok("→ CSV with human headers + projected columns:");
    Console.info(shaped.csv());
  }

  // ---------- columns projection: no failure when a non-requested field is absent ----------

  private static void columnsProjectionDoesNotFail(MockServerControl mock, MarketDataClient client) {
    Console.header("columns projection — non-requested fields come back null, NO error");
    mock.reset();
    // The mock returns ONLY the projected columns (as the real API would for ?columns=...).
    mock.script(Step.of(200, "{\"s\":\"ok\",\"t\":[1705276800],\"c\":[452.84]}"));

    FundCandle bar =
        client
            .funds()
            .columns("t", "c")
            .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"))
            .values()
            .get(0);
    Console.ok("requested → t=" + bar.time() + " c=" + bar.close());
    Console.ok(
        "NOT requested (null, decoded cleanly) → o=" + bar.open() + " h=" + bar.high() + " l="
            + bar.low());
  }

  // ---------- Option A: requested column missing → ParseError ----------

  private static void optionARequestedColumnMissingFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Option A — requested a column the API omitted → ParseError");
    mock.reset();
    // Consumer asks for o, but the body omits it → anomaly, not a projection.
    mock.script(Step.of(200, "{\"s\":\"ok\",\"t\":[1705276800],\"c\":[452.84]}"));

    try {
      client
          .funds()
          .columns("t", "c", "o")
          .candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));
      Console.fail("expected a ParseError — 'o' was requested but the API did not return it");
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
        Step.of(200, "{\"s\":\"ok\",\"t\":[1705276800],\"o\":[451.21],\"h\":[452.84],\"c\":[452.84]}"));

    try {
      // No .columns(...) → every required column is implicitly requested, so the missing `l` fails.
      client.funds().candles(FundCandlesRequest.of(FundResolution.DAILY, "VFINX"));
      Console.fail("expected a ParseError — required columns are missing and none were projected away");
    } catch (ParseError e) {
      Console.ok(
          "ParseError as expected (nullable fields did NOT weaken the strict default): "
              + e.getMessage());
    }
  }
}
