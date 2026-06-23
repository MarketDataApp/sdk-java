package com.marketdata.examples.common;

import com.marketdata.examples.util.MockServer;
import com.marketdata.examples.util.MockServer.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.UtilitiesStatusResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Firing many requests at once &mdash; and what the SDK does about it.
 *
 * <p>Every endpoint has an async variant ({@code statusAsync()}, {@code quoteAsync()}, &hellip;)
 * returning a {@link CompletableFuture}. You can launch hundreds at once and join them with
 * {@link CompletableFuture#allOf}; the SDK caps how many actually hit the network at the same time
 * at 50, queuing the rest. You don't manage that &mdash; this example just makes it visible.
 *
 * <p>It points the SDK at a local mock server so each response can be delayed deterministically and
 * the server can report the peak number of simultaneous requests it saw. Start the mock first:
 * {@code cd ../mock-server && ./run.sh}.
 *
 * <p>Run: {@code ./gradlew runConcurrency}
 */
public final class ConcurrentRequestsExample {

  private ConcurrentRequestsExample() {}

  public static void main(String[] args) {
    MockServer mock = new MockServer();
    mock.requireUp();

    // Script 60 identical responses, each held 800ms so the requests genuinely overlap.
    int fanout = 60;
    mock.scriptRepeated(fanout, Step.of(200, statusBody()).delayMs(800));

    // base URL points at the mock; validateOnStartup=false skips the construct-time probe.
    try (var client = new MarketDataClient("token", MockServer.BASE_URL, null, false)) {

      // Launch all 60 at once — nothing blocks here, every call returns a future immediately.
      List<CompletableFuture<UtilitiesStatusResponse>> futures = new ArrayList<>();
      long start = System.nanoTime();
      for (int i = 0; i < fanout; i++) {
        futures.add(client.utilities().statusAsync());
      }

      // Wait for all of them to finish.
      CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      System.out.println("Launched " + fanout + " async requests at once.");
      System.out.println("All completed in " + elapsedMs + " ms.");
      System.out.println("Peak requests in flight (observed by the server): " + mock.peakInFlight());
      System.out.println();
      System.out.println("The SDK held in-flight requests to 50; the other "
          + (fanout - 50) + " waited and ran once a slot freed up. That's why the wall-clock is");
      System.out.println("about two 800ms waves, not one — and why you can fan out freely without");
      System.out.println("overwhelming the API yourself.");
    }
  }

  private static String statusBody() {
    return "{\"s\":\"ok\",\"service\":[\"/v1/markets/status/\"],\"status\":[\"online\"],"
        + "\"online\":[true],\"uptimePct30d\":[1.0],\"uptimePct90d\":[1.0],\"updated\":[1705449600]}";
  }
}
