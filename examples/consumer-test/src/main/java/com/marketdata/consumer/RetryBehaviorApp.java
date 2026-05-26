package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Walks through the §9 retry policy: which statuses retry, when {@code
 * Retry-After} overrides the exponential backoff, the §21-fix cap on
 * pathological values, and the §10.3 preflight that fails fast when the
 * latest rate-limit snapshot reports remaining=0.
 *
 * <p>Reading the wall-clock printed for each scenario is the point — the SDK's
 * timing IS the spec behavior here.
 *
 * <p>Run: {@code ./gradlew runRetry}
 */
public final class RetryBehaviorApp {
  private RetryBehaviorApp() {}

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client =
        new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      retryRecovers503Then200(mock, client);
      retryAfterDeltaOverridesExponential(mock, client);
      retryAfterHttpDateHonored(mock, client);
      retryAfterPathologicalIsCapped(mock, client);
      preflightBlocksWhenSnapshotExhausted(mock, client);
    }
  }

  // ---------- 503 → 200: retry recovers ----------

  private static void retryRecovers503Then200(MockServerControl mock, MarketDataClient client) {
    Console.header("Retry recovers: 503 → 503 → 200 (≈ 3s wall-time from 1s + 2s backoff)");
    mock.reset();
    String okBody = validStatusBody();
    mock.script(MockServerControl.failNTimesThenSucceed(2, 503, okBody));

    long t0 = System.nanoTime();
    try {
      var resp = client.utilities().status();
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      Console.ok(
          "succeeded after retries; data.services()="
              + resp.data().services().size()
              + ", wall-time="
              + elapsed
              + " ms");
      Console.info("server saw " + mock.stats().requests() + " requests (expected 3)");
    } catch (ServerError e) {
      Console.fail("retries did not recover the call: " + e.getMessage());
    }
  }

  // ---------- Retry-After: delta-seconds overrides exponential ----------

  private static void retryAfterDeltaOverridesExponential(
      MockServerControl mock, MarketDataClient client) {
    Console.header(
        "§9.4: Retry-After: 3 on a 503 overrides the calculated 1s backoff (≈ 3s wait, not 1s)");
    mock.reset();
    mock.script(
        List.of(
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}")
                .withHeader("Retry-After", "3"),
            Step.of(200, validStatusBody())));

    long t0 = System.nanoTime();
    try {
      client.utilities().status();
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      Console.ok("succeeded after " + elapsed + " ms (≈ 3000 — server's hint was honored)");
    } catch (ServerError e) {
      Console.fail("call failed: " + e.getMessage());
    }
  }

  // ---------- Retry-After: HTTP-date variant ----------

  private static void retryAfterHttpDateHonored(MockServerControl mock, MarketDataClient client) {
    Console.header("§9.4: Retry-After accepts HTTP-date (RFC 1123)");
    mock.reset();
    // Pick a future time large enough that the parse-on-the-server-side latency doesn't shrink
    // the resulting delta below the exponential 1s floor (in which case we couldn't tell from
    // wall-clock alone whether the date was honored or the SDK fell back to exponential). With
    // +4s, the delta the SDK computes is always > 1s even after server round-trip latency.
    String inFourSeconds =
        ZonedDateTime.now(ZoneOffset.UTC)
            .plusSeconds(4)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    mock.script(
        List.of(
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}")
                .withHeader("Retry-After", inFourSeconds),
            Step.of(200, validStatusBody())));

    long t0 = System.nanoTime();
    try {
      client.utilities().status();
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      Console.ok(
          "succeeded after "
              + elapsed
              + " ms (HTTP-date parsed → delta from now; ~3-4s wall-time honors the date)");
      if (elapsed < 1500) {
        Console.info(
            "  note: wall-time below ~1.5s suggests the parse failed silently and exponential 1s");
        Console.info(
            "  fired instead — open the SDK log to confirm.");
      }
    } catch (ServerError e) {
      Console.fail("call failed: " + e.getMessage());
    }
  }

  // ---------- Retry-After: pathological value capped (#21 fix) ----------

  private static void retryAfterPathologicalIsCapped(
      MockServerControl mock, MarketDataClient client) {
    Console.header(
        "#21 fix: Retry-After of 1 day on a 503 → capped, SDK falls back to exponential (≈ 1s)");
    mock.reset();
    mock.script(
        List.of(
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}")
                // 86400s = 1 day — well above the 10-minute cap.
                .withHeader("Retry-After", "86400"),
            Step.of(200, validStatusBody())));

    long t0 = System.nanoTime();
    try {
      client.utilities().status();
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      if (elapsed > 30_000) {
        Console.fail("call took " + elapsed + " ms — cap did NOT engage");
      } else {
        Console.ok(
            "succeeded after "
                + elapsed
                + " ms (≈ 1000 — SDK ignored the 1-day directive and used exponential 1s backoff)");
        Console.info(
            "the consumer can still see the raw value on the ServerError via getRetryAfter()");
      }
    } catch (ServerError e) {
      Console.fail("call failed: " + e.getMessage());
    }
  }

  // ---------- §10.3 preflight ----------

  private static void preflightBlocksWhenSnapshotExhausted(
      MockServerControl mock, MarketDataClient client) {
    Console.header(
        "§10.3: snapshot says remaining=0 → preflight fails fast, server sees ZERO additional requests");
    mock.reset();
    // First call: 200 + rate-limit headers reporting remaining=0 with reset in the future.
    long resetEpoch = (System.currentTimeMillis() / 1000L) + 3600L;
    mock.script(
        Step.of(200, validStatusBody())
            .withHeader("x-api-ratelimit-limit", "1000")
            .withHeader("x-api-ratelimit-remaining", "0")
            .withHeader("x-api-ratelimit-reset", String.valueOf(resetEpoch))
            .withHeader("x-api-ratelimit-consumed", "1000"));

    try {
      client.utilities().status();
      Console.ok("first call succeeded; snapshot now says remaining=0");
      Console.info("rateLimits: " + client.getRateLimits());
    } catch (Exception e) {
      Console.fail("first call failed: " + e.getMessage());
    }

    int before = mock.stats().requests();
    Console.step("second call: preflight should block it before it hits the wire");
    long t0 = System.nanoTime();
    try {
      client.utilities().status();
      Console.fail("second call returned — preflight did not engage");
    } catch (RateLimitError e) {
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      Console.ok(
          "RateLimitError raised after "
              + elapsed
              + " ms (instant — no network round-trip)");
      Console.info("message: " + e.getMessage());
    }
    int after = mock.stats().requests();
    if (after == before) {
      Console.ok("server saw 0 additional requests — preflight blocked at the SDK boundary");
    } else {
      Console.fail("server saw " + (after - before) + " additional requests — preflight failed");
    }
  }

  // ---------- helpers ----------

  /** Minimal /status/ payload that ApiStatusDeserializer accepts. */
  private static String validStatusBody() {
    long now = System.currentTimeMillis() / 1000L;
    return "{\"s\":\"ok\","
        + "\"service\":[\"/v1/markets/status/\"],"
        + "\"status\":[\"online\"],"
        + "\"online\":[true],"
        + "\"uptimePct30d\":[1.0],"
        + "\"uptimePct90d\":[1.0],"
        + "\"updated\":["
        + now
        + "]}";
  }
}
