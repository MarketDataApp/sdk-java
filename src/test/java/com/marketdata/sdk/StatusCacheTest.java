package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StatusCacheTest {

  /** Test clock whose instant can be advanced step-by-step. */
  private static final class FixedClock extends Clock {
    private Instant now;

    FixedClock(Instant start) {
      this.now = start;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(java.time.Duration by) {
      now = now.plus(by);
    }
  }

  private static ApiStatus snapshot(String service, String status) {
    return new ApiStatus(
        List.of(
            new ServiceStatus(
                service,
                status,
                "online".equals(status),
                1.0,
                1.0,
                Instant.EPOCH.atZone(MarketDataDates.MARKET_ZONE))));
  }

  // ---------- empty cache ----------

  @Test
  void emptyCacheAllowsAndTriggersRefresh() {
    AtomicInteger calls = new AtomicInteger();
    StatusCache cache =
        new StatusCache(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture(snapshot("/v1/x/", "online"));
            },
            new FixedClock(Instant.now()));

    assertThat(cache.check(URI.create("http://api/v1/x/AAPL/")))
        .isEqualTo(StatusCache.Decision.ALLOW);
    assertThat(calls).hasValue(1);
  }

  // ---------- fresh cache ----------

  @Test
  void freshCacheReturnsOfflineBlock() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    Supplier<CompletableFuture<ApiStatus>> fetcher =
        () -> CompletableFuture.completedFuture(snapshot("/v1/stocks/quotes/", "offline"));
    StatusCache cache = new StatusCache(fetcher, clock);
    cache.triggerRefresh();

    clock.advance(java.time.Duration.ofSeconds(10));
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/stocks/quotes/AAPL/"));

    assertThat(d).isEqualTo(StatusCache.Decision.BLOCK);
  }

  @Test
  void freshCacheReturnsOnlineAllow() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(snapshot("/v1/markets/status/", "online")),
            clock);
    cache.triggerRefresh();

    StatusCache.Decision d = cache.check(URI.create("http://api/v1/markets/status/"));

    assertThat(d).isEqualTo(StatusCache.Decision.ALLOW);
  }

  // ---------- aging cache: serve + refresh ----------

  @Test
  void agingCacheServesAndKicksAsyncRefresh() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    AtomicInteger refreshCalls = new AtomicInteger();
    StatusCache cache =
        new StatusCache(
            () -> {
              refreshCalls.incrementAndGet();
              return CompletableFuture.completedFuture(snapshot("/v1/x/", "online"));
            },
            clock);
    cache.triggerRefresh(); // initial fill
    assertThat(refreshCalls).hasValue(1);

    // Move time to 280s — past refresh threshold, before expiry.
    clock.advance(java.time.Duration.ofSeconds(280));
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/x/"));

    assertThat(d).isEqualTo(StatusCache.Decision.ALLOW); // served from cache
    assertThat(refreshCalls).hasValue(2); // refresh fired
  }

  // ---------- expired cache ----------

  @Test
  void expiredCacheReturnsAllowAndRefreshes() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    AtomicInteger refreshCalls = new AtomicInteger();
    // First call returns synchronously to populate the cache; subsequent calls return a future
    // that never completes — so the refresh is genuinely in-flight when we measure the decision,
    // matching real-world async behavior. With the #19 fix, a synchronous-completing refresh
    // would otherwise immediately overwrite the stale snapshot and the "stale → unknown → ALLOW"
    // invariant would be untestable.
    StatusCache cache =
        new StatusCache(
            () -> {
              int n = refreshCalls.incrementAndGet();
              if (n == 1) {
                return CompletableFuture.completedFuture(snapshot("/v1/x/", "offline"));
              }
              return new CompletableFuture<>(); // never completes
            },
            clock);
    cache.triggerRefresh();
    assertThat(refreshCalls).hasValue(1);

    // 310s — past expiry. Cache is stale → treat as unknown → ALLOW even though the cached
    // entry says offline. The async refresh runs simultaneously.
    clock.advance(java.time.Duration.ofSeconds(310));
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/x/"));

    assertThat(d).isEqualTo(StatusCache.Decision.ALLOW);
    assertThat(refreshCalls).hasValue(2);
  }

  // ---------- in-flight guard ----------

  @Test
  void overlappingRefreshesAreDeduplicatedByInFlightGuard() {
    // The fetcher returns a future that never completes; the in-flight guard must prevent
    // the second/third/fourth check from kicking additional refreshes while the first is open.
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    AtomicInteger fetcherInvocations = new AtomicInteger();
    StatusCache cache =
        new StatusCache(
            () -> {
              fetcherInvocations.incrementAndGet();
              return new CompletableFuture<>(); // never completes
            },
            clock);

    for (int i = 0; i < 5; i++) {
      cache.check(URI.create("http://api/v1/x/"));
    }

    assertThat(fetcherInvocations).hasValue(1);
  }

  // ---------- failure handling ----------

  @Test
  void failedRefreshLeavesPreviousSnapshotIntact() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    AtomicInteger calls = new AtomicInteger();
    StatusCache cache =
        new StatusCache(
            () -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                return CompletableFuture.completedFuture(snapshot("/v1/x/", "offline"));
              }
              // Second call fails — simulate /status/ briefly down.
              return CompletableFuture.failedFuture(new RuntimeException("status endpoint down"));
            },
            clock);
    cache.triggerRefresh();
    // Cache now has /v1/x/ -> offline.

    // Trigger refresh: it fails.
    clock.advance(java.time.Duration.ofSeconds(280));
    cache.check(URI.create("http://api/v1/x/"));

    // Cache still serves the previous snapshot.
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/x/"));
    assertThat(d).isEqualTo(StatusCache.Decision.BLOCK);
  }

  @Test
  void fetcherThrowingSyncAlsoLeavesCacheIntact() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    AtomicInteger calls = new AtomicInteger();
    StatusCache cache =
        new StatusCache(
            () -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                return CompletableFuture.completedFuture(snapshot("/v1/x/", "offline"));
              }
              throw new IllegalStateException("synchronous failure");
            },
            clock);
    cache.triggerRefresh();

    clock.advance(java.time.Duration.ofSeconds(280));
    cache.check(URI.create("http://api/v1/x/")); // triggers refresh; sync throws
    // The in-flight guard must reset so subsequent refresh attempts can proceed.

    clock.advance(java.time.Duration.ofSeconds(50));
    cache.check(URI.create("http://api/v1/x/")); // age > 300, triggers again
    assertThat(calls).hasValue(3); // initial fill + 2 failed refreshes
  }

  // ---------- URI → service matching ----------

  @Test
  void uriMatchesLongestServicePrefix() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    StatusCache cache =
        new StatusCache(
            () ->
                CompletableFuture.completedFuture(
                    new ApiStatus(
                        List.of(
                            new ServiceStatus(
                                "/v1/",
                                "online",
                                true,
                                1.0,
                                1.0,
                                Instant.EPOCH.atZone(MarketDataDates.MARKET_ZONE)),
                            new ServiceStatus(
                                "/v1/stocks/quotes/",
                                "offline",
                                false,
                                0.5,
                                0.6,
                                Instant.EPOCH.atZone(MarketDataDates.MARKET_ZONE))))),
            clock);
    cache.triggerRefresh();

    // /v1/stocks/quotes/AAPL/ matches both /v1/ and /v1/stocks/quotes/ — longest wins.
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/stocks/quotes/AAPL/"));

    assertThat(d).isEqualTo(StatusCache.Decision.BLOCK);
  }

  @Test
  void uriWithNoMatchingServiceTreatsAsUnknown() {
    // The /status/ endpoint itself has no matching service entry — its own call must not
    // recurse on offline lookups. Test that the URI of /status/ falls through to ALLOW.
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(snapshot("/v1/x/", "offline")), clock);
    cache.triggerRefresh();

    StatusCache.Decision d = cache.check(URI.create("http://api/status/"));

    assertThat(d).isEqualTo(StatusCache.Decision.ALLOW);
  }

  /**
   * Issue #18: a truncated or malformed service entry like {@code /v1/stock} must NOT match the
   * unrelated {@code /v1/stocks/...} endpoint. Without path-boundary normalization, one bad
   * snapshot entry could block retries across an entire family of services.
   */
  @Test
  void shorterServiceKeyDoesNotFalselyMatchLongerPath() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    // Note: `/v1/stock` (no trailing slash, no `s`) — a truncated entry. With the trailing-slash
    // normalization the cache key becomes `/v1/stock/`, which is NOT a path prefix of
    // `/v1/stocks/quotes/AAPL/`.
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(snapshot("/v1/stock", "offline")), clock);
    cache.triggerRefresh();

    StatusCache.Decision d = cache.check(URI.create("http://api/v1/stocks/quotes/AAPL/"));

    assertThat(d).isEqualTo(StatusCache.Decision.ALLOW);
  }

  /**
   * Issue #18 positive case: a key that IS a real path-prefix still matches even when the server
   * emits it without the trailing slash. The normalization is two-way (key + lookup path).
   */
  @Test
  void serverKeyWithoutTrailingSlashStillMatchesGenuinePrefix() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(snapshot("/v1/stocks/quotes", "offline")),
            clock);
    cache.triggerRefresh();

    StatusCache.Decision d = cache.check(URI.create("http://api/v1/stocks/quotes/AAPL/"));

    assertThat(d).isEqualTo(StatusCache.Decision.BLOCK);
  }

  /**
   * Issue #19: with a synchronous fetcher, the snapshot is populated before {@code check} returns,
   * so a cold start against a known-offline service must already see the BLOCK decision on the
   * first call. Pre-fix the local {@code snap} reference was captured before {@code triggerRefresh}
   * and never re-read, so the first call always answered ALLOW — burning the retry budget against a
   * service the API had just reported offline.
   */
  @Test
  void coldStartWithSyncFetcherUsesFreshSnapshotOnFirstCall() {
    FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
    StatusCache cache =
        new StatusCache(
            () -> CompletableFuture.completedFuture(snapshot("/v1/x/", "offline")), clock);

    // No pre-warm — this is the very first check. The fetcher completes synchronously inside
    // triggerRefresh and must populate the snapshot in time for the same call to use it.
    StatusCache.Decision d = cache.check(URI.create("http://api/v1/x/"));

    assertThat(d).isEqualTo(StatusCache.Decision.BLOCK);
  }
}
