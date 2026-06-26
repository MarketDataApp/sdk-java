package com.marketdata.examples.common;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.UtilitiesStatusResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Every endpoint comes in two flavours: a blocking call ({@code status()}) and an async one
 * ({@code statusAsync()}) returning a {@link CompletableFuture}. They share all the same validation,
 * retry and rate-limit logic &mdash; pick whichever fits your code.
 *
 * <p>Uses the public {@code utilities().status()} endpoint, so it runs without a token.
 *
 * <p>Run: {@code ./gradlew runSyncVsAsync}
 */
public final class SyncVsAsyncExample {

  private SyncVsAsyncExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient(null, null, null, false)) {

      // Sync: the call blocks until the response is ready, then returns it.
      var sync = client.utilities().status();
      System.out.println("Sync:  " + sync.values().size() + " services online");

      // Async: the call returns immediately with a future. Attach a callback, or join() to block.
      // Same return type as the sync call once it completes.
      CompletableFuture<UtilitiesStatusResponse> future = client.utilities().statusAsync();
      future.thenAccept(resp ->
          System.out.println("Async: " + resp.values().size() + " services online (callback)"));
      future.join(); // wait so the example doesn't exit before the callback runs

      // Where async pays off: fire several calls at once and wait for all of them. Total time is
      // about the slowest single call, not the sum — they overlap on the network. (Here it's three
      // status calls so the example needs no token; in a real app you'd fan out different symbols or
      // endpoints the same way.)
      System.out.println("\nFanning out 3 calls in parallel:");
      long start = System.nanoTime();
      CompletableFuture<UtilitiesStatusResponse> a = client.utilities().statusAsync();
      CompletableFuture<UtilitiesStatusResponse> b = client.utilities().statusAsync();
      CompletableFuture<UtilitiesStatusResponse> c = client.utilities().statusAsync();

      CompletableFuture.allOf(a, b, c).join();
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      System.out.println("All 3 done in " + elapsedMs + " ms (≈ one round-trip, not three).");

    } catch (Exception e) {
      System.out.println("Call failed: " + e.getClass().getSimpleName() + " — " + e.getMessage());
    }
  }
}
