package com.marketdata.sdk;

import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Client-side cache of the {@code /status/} endpoint used to gate retries against services the API
 * has reported offline (SDK requirements §9.5).
 *
 * <p>The TTL is a stale-while-revalidate window:
 *
 * <ul>
 *   <li>{@code age < 270s} — serve the cached snapshot, no refresh.
 *   <li>{@code 270s ≤ age < 300s} — serve the cached snapshot AND fire an async refresh.
 *   <li>{@code age ≥ 300s} or no cache — treat the service as {@code unknown} (which allows
 *       retries) AND fire an async refresh.
 * </ul>
 *
 * <p>The refresh fetcher returns a {@link CompletableFuture}, so refreshes never block the caller.
 * If a refresh fails, the previous snapshot survives — there is no fallback "assume online"; the
 * SDK simply continues using what it knows until the next successful refresh.
 *
 * <p>A single {@link AtomicBoolean} guards refreshes so concurrent retries on different services
 * don't fire N refreshes against the same {@code /status/} endpoint.
 *
 * <p>Decision matrix per {@link Decision}: {@code offline} services {@link Decision#BLOCK} retries;
 * everything else (including the {@code unknown} that comes from a stale or empty cache) {@link
 * Decision#ALLOW}s them, per §9.5.
 */
final class StatusCache {

  private static final Logger LOGGER = Logger.getLogger(StatusCache.class.getName());

  static final Duration REFRESH_THRESHOLD = Duration.ofSeconds(270);
  static final Duration EXPIRY = Duration.ofSeconds(300);

  private final Supplier<CompletableFuture<ApiStatus>> fetcher;
  private final Clock clock;
  private final AtomicReference<@Nullable Snapshot> snapshot = new AtomicReference<>();
  private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

  StatusCache(Supplier<CompletableFuture<ApiStatus>> fetcher, Clock clock) {
    this.fetcher = fetcher;
    this.clock = clock;
  }

  /** Whether retrying on {@code uri} is allowed by the cache. */
  Decision check(URI uri) {
    Snapshot snap = snapshot.get();
    Instant now = clock.instant();

    boolean refreshNeeded =
        snap == null || Duration.between(snap.fetchedAt, now).compareTo(REFRESH_THRESHOLD) >= 0;
    if (refreshNeeded) {
      triggerRefresh();
    }

    boolean usable = snap != null && Duration.between(snap.fetchedAt, now).compareTo(EXPIRY) < 0;
    if (!usable) {
      // Stale or empty → "unknown" → allow per §9.5.
      return Decision.ALLOW;
    }

    String status = lookupService(snap, uri);
    return "offline".equals(status) ? Decision.BLOCK : Decision.ALLOW;
  }

  /** Manually trigger a refresh. Visible for tests; production calls only via {@link #check}. */
  void triggerRefresh() {
    if (!refreshInFlight.compareAndSet(false, true)) {
      return; // already refreshing
    }
    CompletableFuture<ApiStatus> future;
    try {
      future = fetcher.get();
    } catch (Throwable t) {
      // Sync-throw from the fetcher (rare — most failures arrive as a failed future). Log so a
      // permanently-broken fetcher doesn't degrade silently into "stale snapshot forever".
      LOGGER.log(
          Level.WARNING, "StatusCache fetcher threw synchronously; snapshot persists.", t);
      refreshInFlight.set(false);
      return;
    }
    future.whenComplete(
        (apiStatus, err) -> {
          try {
            if (err == null && apiStatus != null) {
              snapshot.set(Snapshot.from(apiStatus, clock.instant()));
            } else if (err != null) {
              // On error: cache persists — §9.5 "Cache persists across failed refresh attempts" —
              // but the failure is logged so operators can detect a /status/ outage instead of
              // wondering why the SDK keeps blocking retries against a stale snapshot.
              LOGGER.log(Level.WARNING, "StatusCache refresh failed; snapshot persists.", err);
            }
          } finally {
            refreshInFlight.set(false);
          }
        });
  }

  /**
   * Find the cached status for the service whose path is the longest prefix of {@code uri}'s path.
   * Returns {@code null} when no service matches.
   */
  private static @Nullable String lookupService(Snapshot snap, URI uri) {
    String path = uri.getPath();
    String bestKey = null;
    for (String key : snap.serviceToStatus.keySet()) {
      if (path.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
        bestKey = key;
      }
    }
    return bestKey == null ? null : snap.serviceToStatus.get(bestKey);
  }

  /** Decision the gate returns to the retry executor. */
  enum Decision {
    /** Cache permits a retry: service is online, unknown, or out-of-scope. */
    ALLOW,
    /** Cache marked the affected service offline — fail immediately without retrying. */
    BLOCK
  }

  /** Immutable snapshot of one /status/ response, indexed by service path. */
  private record Snapshot(Instant fetchedAt, Map<String, String> serviceToStatus) {
    static Snapshot from(ApiStatus apiStatus, Instant fetchedAt) {
      Map<String, String> map = new HashMap<>(apiStatus.services().size());
      for (ServiceStatus s : apiStatus.services()) {
        map.put(s.service(), s.status());
      }
      return new Snapshot(fetchedAt, Map.copyOf(map));
    }
  }
}
