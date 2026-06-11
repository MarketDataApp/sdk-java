package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.DateFormat;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Mode;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.time.LocalDate;
import java.util.List;

/**
 * Exhaustive {@code markets} resource demo against the mock server. Covers:
 *
 * <ul>
 *   <li>the single endpoint ({@code status}) with its full parameter surface — universal params
 *       (dateFormat/mode/limit/offset) + the three window shapes ({@code from}/{@code to}, {@code
 *       date}, {@code to}+{@code countback}) and {@code country}, including the bare no-args
 *       request (today's status);
 *   <li>the null-status-cell case: days outside the backend's holiday-calendar coverage come back
 *       with a {@code null} status cell — decoded to {@code null}, not an error;
 *   <li>the CSV facet ({@code asCsv()}) including the output-shaping {@code columns}/{@code
 *       human}/{@code headers} params;
 *   <li>{@code columns} projection: requested fields populate, fields you did <em>not</em> ask for
 *       come back {@code null} with <strong>no error</strong>;
 *   <li>Option A failures: a required column you <em>did</em> request (or didn't project away)
 *       that the API omits raises a {@link ParseError};
 *   <li>§8.2 per-response rate limits: {@code rateLimit()} parsed from each response's headers.
 * </ul>
 *
 * <p>Each scenario scripts the mock server's response with {@link MockServerControl#script}.
 *
 * <p>Run: {@code ./gradlew runMarkets} (needs the mock server up).
 */
public final class MarketsApp {

  private MarketsApp() {}

  // Fri open, Sat/Sun closed — the market open/closed calendar, NOT the API health endpoint.
  private static final String STATUS =
      "{\"s\":\"ok\","
          + "\"date\":[1705035600,1705122000,1705208400],"
          + "\"status\":[\"open\",\"closed\",\"closed\"]}";

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      statusWithParams(mock, client);
      nullStatusOutsideCalendarCoverage(mock, client);
      perResponseRateLimit(mock, client);
      csvFacet(mock, client);
      columnsProjectionDoesNotFail(mock, client);
      optionARequestedColumnMissingFails(mock, client);
      strictByDefaultMissingColumnFails(mock, client);
    }
  }

  // ---------- the status endpoint, all parameter shapes ----------

  private static void statusWithParams(MockServerControl mock, MarketDataClient client) {
    Console.header("markets.status — the parameter surface");

    // Bare request: every parameter is optional — today's status, US calendar.
    Console.step("status(MarketStatusRequest.of()) — no params: today's status");
    mock.reset();
    mock.script(Step.of(200, STATUS));
    var today = client.markets().status(MarketStatusRequest.of());
    Console.ok("status.values() → " + today.values().size() + " day(s); iterating:");
    for (MarketStatus day : today.values()) {
      Console.info(
          "  " + day.date().toLocalDate() + "  status=" + day.status() + "  isOpen="
              + day.isOpen());
    }

    // from/to range + country + universal params, set fluently.
    Console.step("status(from/to + country) + universal params (dateFormat/mode/limit)");
    mock.reset();
    mock.script(Step.of(200, STATUS));
    var range =
        client
            .markets()
            .dateFormat(DateFormat.UNIX) // universal param (type-preserving)
            .mode(Mode.DELAYED) // universal param
            .limit(500) // universal param
            .status(
                MarketStatusRequest.builder()
                    .country("US")
                    .from(LocalDate.of(2024, 1, 12))
                    .to(LocalDate.of(2024, 1, 14))
                    .build());
    List<MarketStatus> days = range.values(); // List<MarketStatus>
    long open = days.stream().filter(MarketStatus::isOpen).count();
    Console.ok("status.values() → " + days.size() + " days, " + open + " open");

    // Single-day lookup: date is mutually exclusive with from/to/countback.
    Console.step("status(date=...) — was the market open on a specific day?");
    mock.reset();
    mock.script(Step.of(200, "{\"s\":\"ok\",\"date\":[1705035600],\"status\":[\"open\"]}"));
    var single =
        client
            .markets()
            .status(MarketStatusRequest.builder().date(LocalDate.of(2024, 1, 12)).build());
    Console.ok("→ " + (single.values().get(0).isOpen() ? "open" : "closed"));

    // to + countback: "the last N days ending at `to`" — no left edge needed.
    Console.step("status(to=..., countback=30) — the last 30 days");
    mock.reset();
    mock.script(Step.of(200, STATUS));
    var counted =
        client
            .markets()
            .status(
                MarketStatusRequest.builder()
                    .to(LocalDate.of(2024, 1, 14))
                    .countback(30)
                    .build());
    Console.ok("status.values() → " + counted.values().size() + " days");
  }

  // ---------- markets-specific: null status cells outside calendar coverage ----------

  private static void nullStatusOutsideCalendarCoverage(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Null status cells — days outside the holiday-calendar coverage");
    mock.reset();
    // The backend's holiday data is bounded; days beyond it get a null status CELL (the column is
    // present, so Option A is satisfied — this is data, not an anomaly).
    mock.script(
        Step.of(200, "{\"s\":\"ok\",\"date\":[1705035600,4102462800],\"status\":[\"open\",null]}"));
    Console.step("status(...) — second day beyond the calendar bounds");
    var resp = client.markets().status(MarketStatusRequest.of());
    MarketStatus known = resp.values().get(0);
    MarketStatus unknown = resp.values().get(1);
    Console.ok("inside coverage  → status=" + known.status() + " isOpen=" + known.isOpen());
    Console.ok(
        "outside coverage → status="
            + unknown.status()
            + " (null cell decoded cleanly; isOpen="
            + unknown.isOpen()
            + ", isClosed="
            + unknown.isClosed()
            + ")");
    Console.info("A null status means \"the calendar has no answer\", never a decode failure.");
  }

  // ---------- §8.2 per-response rate limit ----------

  private static void perResponseRateLimit(MockServerControl mock, MarketDataClient client) {
    Console.header("§8.2 per-response rate limit — rateLimit() off each response");
    mock.reset();
    // Script the four x-api-ratelimit-* headers on the response; the SDK parses them per response.
    mock.script(
        Step.of(200, STATUS)
            .withHeader("x-api-ratelimit-limit", "100000")
            .withHeader("x-api-ratelimit-remaining", "99997")
            .withHeader("x-api-ratelimit-reset", "1735689600")
            .withHeader("x-api-ratelimit-consumed", "3"));
    Console.step("status(...).rateLimit() — parsed from THIS response's headers");
    var resp = client.markets().status(MarketStatusRequest.of());
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
    Console.header("CSV facet — client.markets().asCsv()");

    Console.step("asCsv().status(...) — plain CSV");
    mock.reset();
    mock.script(Step.of(200, "date,status\n1705035600,open\n1705122000,closed"));
    var csv = client.markets().asCsv().status(MarketStatusRequest.of());
    Console.ok("→ CsvResponse (" + csv.csv().length() + " chars):");
    Console.info(csv.csv());

    // columns / human / headers reshape the output, so they live ONLY on the CSV facet.
    Console.step(
        "asCsv().columns(...).human(true).headers(true) — output-shaping params (CSV-only)");
    mock.reset();
    mock.script(Step.of(200, "Date,Status\n2024-01-12,open\n2024-01-13,closed"));
    var shaped =
        client
            .markets()
            .asCsv()
            .columns("date", "status")
            .human(true)
            .headers(true)
            .status(MarketStatusRequest.of());
    Console.ok("→ CSV with human headers + projected columns:");
    Console.info(shaped.csv());
  }

  // ---------- columns projection: no failure when a non-requested field is absent ----------

  private static void columnsProjectionDoesNotFail(
      MockServerControl mock, MarketDataClient client) {
    Console.header("columns projection — non-requested fields come back null, NO error");
    mock.reset();
    // The mock returns ONLY the projected columns (as the real API would for ?columns=...).
    mock.script(Step.of(200, "{\"s\":\"ok\",\"status\":[\"open\"]}"));

    MarketStatus day =
        client.markets().columns("status").status(MarketStatusRequest.of()).values().get(0);
    Console.ok("requested → status=" + day.status());
    Console.ok("NOT requested (null, decoded cleanly) → date=" + day.date());
  }

  // ---------- Option A: requested column missing → ParseError ----------

  private static void optionARequestedColumnMissingFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Option A — requested a column the API omitted → ParseError");
    mock.reset();
    // Consumer asks for date, but the body omits it → anomaly, not a projection.
    mock.script(Step.of(200, "{\"s\":\"ok\",\"status\":[\"open\"]}"));

    try {
      client.markets().columns("date", "status").status(MarketStatusRequest.of());
      Console.fail("expected a ParseError — 'date' was requested but the API did not return it");
    } catch (ParseError e) {
      Console.ok("ParseError as expected: " + e.getMessage());
    }
  }

  // ---------- strict by default: no columns filter still requires all structural columns ----------

  private static void strictByDefaultMissingColumnFails(
      MockServerControl mock, MarketDataClient client) {
    Console.header("Strict by default — no columns filter, but a required column is missing");
    mock.reset();
    mock.script(Step.of(200, "{\"s\":\"ok\",\"date\":[1705035600]}"));

    try {
      // No .columns(...) → every required column is implicitly requested, so the missing `status`
      // fails.
      client.markets().status(MarketStatusRequest.of());
      Console.fail(
          "expected a ParseError — required columns are missing and none were projected away");
    } catch (ParseError e) {
      Console.ok(
          "ParseError as expected (nullable fields did NOT weaken the strict default): "
              + e.getMessage());
    }
  }
}
