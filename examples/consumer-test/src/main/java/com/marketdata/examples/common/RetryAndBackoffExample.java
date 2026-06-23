package com.marketdata.examples.common;

import com.marketdata.examples.util.MockServer;
import com.marketdata.examples.util.MockServer.Step;
import com.marketdata.sdk.MarketDataClient;
import java.util.List;

/**
 * What the SDK does when the API has a transient hiccup: it retries automatically.
 *
 * <p>Server errors (HTTP 503 and other 5xx) and network errors are retried with exponential backoff
 * (≈1s, then 2s, then 4s) up to 3 times before the SDK gives up and throws. A {@code Retry-After}
 * header on the response overrides that schedule. You don't write any of this &mdash; it just
 * happens around every call. This example makes it visible with a scripted local server.
 *
 * <p>Start the mock first: {@code cd ../mock-server && ./run.sh}.
 *
 * <p>Run: {@code ./gradlew runRetry}
 */
public final class RetryAndBackoffExample {

  private RetryAndBackoffExample() {}

  public static void main(String[] args) {
    MockServer mock = new MockServer();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServer.BASE_URL, null, false)) {
      retryRecovers(mock, client);
      retryAfterHonored(mock, client);
    }
  }

  /** Two 503s then a 200: the call recovers on its own, after the backoff waits. */
  private static void retryRecovers(MockServer mock, MarketDataClient client) {
    System.out.println("=== Transient 503s, then success ===");
    mock.script(List.of(
        Step.of(503, "{\"s\":\"error\",\"errmsg\":\"temporarily down\"}"),
        Step.of(503, "{\"s\":\"error\",\"errmsg\":\"temporarily down\"}"),
        Step.of(200, statusBody())));

    long start = System.nanoTime();
    var resp = client.utilities().status();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println("Call succeeded with " + resp.values().size() + " services.");
    System.out.println("It took ~" + elapsedMs + " ms — the SDK silently retried twice (≈1s + 2s "
        + "backoff) before the third attempt returned 200.\n");
  }

  /** A 503 carrying Retry-After: 3 — the SDK waits exactly that long instead of its own schedule. */
  private static void retryAfterHonored(MockServer mock, MarketDataClient client) {
    System.out.println("=== Server says Retry-After: 3 ===");
    mock.script(List.of(
        Step.of(503, "{\"s\":\"error\",\"errmsg\":\"slow down\"}").header("Retry-After", "3"),
        Step.of(200, statusBody())));

    long start = System.nanoTime();
    client.utilities().status();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println("Call succeeded after ~" + elapsedMs + " ms (≈3000) — the SDK honored the "
        + "server's Retry-After hint instead of its default 1s backoff.");
  }

  private static String statusBody() {
    return "{\"s\":\"ok\",\"service\":[\"/v1/markets/status/\"],\"status\":[\"online\"],"
        + "\"online\":[true],\"uptimePct30d\":[1.0],\"uptimePct90d\":[1.0],\"updated\":[1705449600]}";
  }
}
