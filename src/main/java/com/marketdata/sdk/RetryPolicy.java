package com.marketdata.sdk;

import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ServerError;
import java.time.Duration;

/**
 * Decides which failures get retried and how long to wait between attempts. SDK requirements §9
 * fixes the parameters: max 3 attempts total (one initial + two retries), exponential backoff
 * starting at 1s, capped at 30s. Network errors and HTTP 501–599 are retriable; 500 specifically is
 * not. Everything 4xx (including 401/429) surfaces immediately.
 *
 * <p>The constructor accepts custom values so tests can drive retries with sub-millisecond delays
 * without waiting on real wall-clock backoffs.
 */
final class RetryPolicy {

  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Duration maxBackoff;

  RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
    }
    this.maxAttempts = maxAttempts;
    this.initialBackoff = initialBackoff;
    this.maxBackoff = maxBackoff;
  }

  /** Spec defaults: 3 attempts, 1s → 30s exponential. */
  static RetryPolicy defaults() {
    return new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30));
  }

  /**
   * Whether the SDK should retry after {@code cause}, given that {@code attempt} attempts have
   * already been spent (zero-indexed: {@code attempt == 0} means the original call just failed and
   * we're considering the first retry).
   */
  boolean shouldRetry(Throwable cause, int attempt) {
    if (attempt + 1 >= maxAttempts) {
      return false;
    }
    return isRetriable(cause);
  }

  /**
   * Backoff before the next attempt. {@code attempt == 0} means "before the first retry", i.e. the
   * delay applied right after the original call failed.
   */
  Duration backoffDelay(int attempt) {
    long base = initialBackoff.toMillis();
    long max = maxBackoff.toMillis();
    // attempt 0 → base * 1, attempt 1 → base * 2, attempt N → base * 2^N. Long arithmetic with
    // an explicit cap because shifting >= 63 bits is undefined in Java; also avoids overflow if
    // a misconfigured policy used a huge attempt count.
    long delay;
    if (attempt >= 62) {
      delay = max;
    } else {
      long multiplier = 1L << attempt;
      delay = (base > max / multiplier) ? max : base * multiplier;
    }
    return Duration.ofMillis(Math.min(delay, max));
  }

  private static boolean isRetriable(Throwable cause) {
    if (!(cause instanceof MarketDataException)) {
      // Conservative: unknown failure types don't get retried. The caller sees the original
      // exception rather than an amplified series of identical hits.
      return false;
    }
    if (cause instanceof NetworkError) {
      return true;
    }
    if (cause instanceof ServerError server) {
      Integer status = server.getStatusCode();
      // Spec §9: 500 is not retriable; 501–599 are. A null status means "we threw a ServerError
      // without a real HTTP code" — that's only the synthetic-path of HttpStatusMapper today, so
      // don't retry it.
      return status != null && status >= 501 && status <= 599;
    }
    // AuthenticationError, BadRequestError, RateLimitError, NotFoundError, ParseError: §9 says
    // never retry 4xx, and ParseError is deterministic.
    return false;
  }
}
