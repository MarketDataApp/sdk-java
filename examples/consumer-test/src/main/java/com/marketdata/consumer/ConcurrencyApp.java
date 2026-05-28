package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Response;
import com.marketdata.sdk.utilities.ApiStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * §12 / ADR-007 concurrency: the SDK's AsyncSemaphore holds at most 50
 * in-flight HTTP requests. This demo fires 60 calls in parallel against the
 * mock server, asks each one to hang for 800 ms, and then reads
 * /_admin/stats — the {@code peak_in_flight} the server observed should be
 * exactly 50.
 *
 * <p>The other 10 requests sit in the semaphore's wait queue and drain after
 * the first batch completes, so the total wall-clock is ≈ 2 × 800 ms even
 * though all 60 were dispatched at t=0.
 *
 * <p>Run: {@code ./gradlew runConcurrency}
 */
public final class ConcurrencyApp {
  private ConcurrencyApp() {}

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();
    mock.reset();

    // Script 60 identical slow responses so the SDK's semaphore is the only thing throttling.
    int fanout = 60;
    int delayMs = 800;
    String okBody = validStatusBody();
    List<Step> steps = new ArrayList<>(fanout);
    for (int i = 0; i < fanout; i++) {
      steps.add(Step.of(200, okBody).delayMs(delayMs));
    }
    mock.script(steps);

    Console.header(
        "Firing " + fanout + " parallel async calls; each response delayed " + delayMs + " ms");
    Console.info(
        "Expectation: peak_in_flight = 50 (the ADR-007 semaphore cap), total wall-clock ≈ "
            + (delayMs * 2)
            + " ms (2 batches: 50 then 10).");

    try (var client =
        new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      long t0 = System.nanoTime();
      List<CompletableFuture<Response<ApiStatus>>> futures = new ArrayList<>(fanout);
      for (int i = 0; i < fanout; i++) {
        futures.add(client.utilities().statusAsync());
      }
      CompletableFuture<Void> all =
          CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
      all.join();
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

      Console.ok("all " + fanout + " calls completed in " + elapsedMs + " ms");
      MockServerControl.Stats stats = mock.stats();
      Console.info("server saw " + stats.requests() + " requests (expected " + fanout + ")");
      Console.info("peak_in_flight observed by server: " + stats.peakInFlight());

      if (stats.peakInFlight() == 50) {
        Console.ok("§12 honored exactly: 50 concurrent, no more, no less.");
      } else if (stats.peakInFlight() < 50) {
        Console.fail(
            "peak below cap ("
                + stats.peakInFlight()
                + ") — system was slow to dispatch, retry on a quiet machine");
      } else {
        Console.fail("peak ABOVE cap (" + stats.peakInFlight() + ") — §12 violated");
      }
    }
  }

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
