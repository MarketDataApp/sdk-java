package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatus;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Drives a {@code /v1/markets/*} call through either the sync or async surface. ASYNC mode unwraps
 * {@link CompletionException} so caller-visible behavior matches sync (per ADR-006: sync wraps
 * {@code .join()} and surfaces the underlying cause directly).
 *
 * <p>Lives in the unit-test source set so it is reusable from the integration-test source set —
 * {@code integrationTest}'s compileClasspath includes the unit-test output (see {@code
 * build.gradle.kts}). Package-private intentionally: only test classes in {@code
 * com.marketdata.sdk} need it.
 */
enum CallMode {
  SYNC {
    @Override
    MarketStatus statusNoArgs(MarketsResource r) {
      return r.status();
    }

    @Override
    MarketStatus statusForDate(MarketsResource r, LocalDate date) {
      return r.status(date);
    }

    @Override
    MarketStatus statusForRange(MarketsResource r, LocalDate from, LocalDate to) {
      return r.status(from, to);
    }
  },
  ASYNC {
    @Override
    MarketStatus statusNoArgs(MarketsResource r) {
      return joinUnwrapping(r.statusAsync());
    }

    @Override
    MarketStatus statusForDate(MarketsResource r, LocalDate date) {
      return joinUnwrapping(r.statusAsync(date));
    }

    @Override
    MarketStatus statusForRange(MarketsResource r, LocalDate from, LocalDate to) {
      return joinUnwrapping(r.statusAsync(from, to));
    }
  };

  abstract MarketStatus statusNoArgs(MarketsResource r);

  abstract MarketStatus statusForDate(MarketsResource r, LocalDate date);

  abstract MarketStatus statusForRange(MarketsResource r, LocalDate from, LocalDate to);

  private static <T> T joinUnwrapping(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw e;
    }
  }
}
