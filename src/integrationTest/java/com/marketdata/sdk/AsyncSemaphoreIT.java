package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.markets.MarketStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency integration test against the live Market Data API. Verifies that the {@link
 * AsyncSemaphore} + {@link HttpTransport} pipeline correctly handles fan-out beyond the pool size:
 * the requests over the limit must traverse the semaphore's slow path (queue the waiter, complete
 * it later via {@code release}) without deadlocking or losing a permit.
 *
 * <p>Costs {@code CONCURRENCY_LIMIT + 5 = 55} requests against the live {@code /markets/status/}
 * endpoint per run. With a typical RTT of ~100 ms and pool size 50, the test wall time is well
 * under a second.
 *
 * <p>Gated by {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} like the rest of this source set.
 */
class AsyncSemaphoreIT {

  /**
   * If a permit ever leaked or the slow-path queue stopped being drained, {@code allOf.join()}
   * would block forever. The 30 s timeout fails the test fast instead of leaving CI hung.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void concurrentFanOutBeyondPoolLimitCompletesWithoutDeadlock() {
    try (var client = new MarketDataClient(null, null, null, false)) {
      int n = HttpTransport.CONCURRENCY_LIMIT + 5;
      List<CompletableFuture<MarketStatus>> futures = new ArrayList<>(n);

      // Fire all N requests as fast as the loop runs. With pool=50, the first 50 take the
      // fast path (already-completed acquire future) and dispatch immediately; requests
      // 51..55 take the slow path and enqueue waiters that complete only when one of the
      // first 50 releases.
      for (int i = 0; i < n; i++) {
        futures.add(client.markets().statusAsync());
      }

      // allOf.join() throws on any underlying failure; we let it propagate so a 429 / network
      // hiccup surfaces as a real test failure rather than silently masking the issue.
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Every response must be a valid MarketStatus. Empty results would suggest a hidden
      // failure (auth issue, rate limit) that wasn't observable from allOf alone.
      for (CompletableFuture<MarketStatus> f : futures) {
        MarketStatus status = f.join();
        assertThat(status.days()).isNotEmpty();
        assertThat(status.days().get(0).date()).isNotNull();
      }
    }
  }
}
