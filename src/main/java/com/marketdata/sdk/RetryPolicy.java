package com.marketdata.sdk;

import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ServerError;
import java.io.IOException;
import java.time.Duration;

/**
 * Decides which failures get retried and how long to wait between attempts. Per SDK requirements
 * §9.3: max 3 retries (yielding 4 total attempts) with exponential backoff {@code initial *
 * 2^retry} starting at 1s, capped at 30s. Network errors (only when wrapping an {@link
 * IOException}-shaped cause — see {@link #shouldRetry}) and HTTP 501–599 are retriable; 500
 * specifically is not, and 4xx (including 401/429) surfaces immediately.
 *
 * <p><strong>Worst-case wall-clock per {@code executeAsync} call (defaults):</strong> 4 attempts ×
 * 99s per-request timeout + 1s + 2s + 4s backoff ≈ 6.75 minutes. SDK requirements §10 only mandates
 * the per-request timeout, not an overall deadline, so this is compliant — but callers in
 * latency-sensitive contexts may want to wrap calls with their own {@code orTimeout} cap.
 *
 * <p>The constructor accepts custom values so tests can drive retries with sub-millisecond delays
 * without waiting on real wall-clock backoffs.
 *
 * <p>§9.4 {@code Retry-After} override: when the failing cause is a {@link ServerError} that
 * carries a server-supplied delay, {@link #backoffDelay(Throwable, int)} returns that delay
 * verbatim instead of the exponential calculation. §9.5 {@code /status/} cache pre-check is handled
 * at the {@code HttpTransport} layer via {@code StatusCache}, not here — that gate depends on
 * external runtime state this class doesn't see.
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

  /** Defaults: 4 attempts, 1s → 30s exponential. */
  static RetryPolicy defaults() {
    return new RetryPolicy(4, Duration.ofSeconds(1), Duration.ofSeconds(30));
  }

  /**
   * Single-attempt policy: {@code shouldRetry} always returns {@code false}. Useful for calls where
   * retrying does more harm than failing fast — e.g. the startup validation in {@link
   * MarketDataClient}, where a slow/down API should surface to the constructor within seconds
   * rather than burning the full ~6.75 min default retry budget before throwing.
   */
  static RetryPolicy noRetry() {
    return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
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
   * Backoff before the next attempt, honoring a server-supplied {@code Retry-After} when the cause
   * is a {@link ServerError} that carried one (§9.4). Otherwise falls back to the exponential
   * calculation from {@link #backoffDelay(int)}.
   */
  Duration backoffDelay(Throwable cause, int attempt) {
    if (cause instanceof ServerError server) {
      Duration override = server.getRetryAfter().orElse(null);
      if (override != null) {
        return override;
      }
    }
    return backoffDelay(attempt);
  }

  /**
   * Backoff before the next attempt. {@code attempt == 0} means "before the first retry", i.e. the
   * delay applied right after the original call failed.
   */
  Duration backoffDelay(int attempt) {
    long base = initialBackoff.toMillis();
    long max = maxBackoff.toMillis();
    // Two saturation points: (1) for large attempt indices, the shift `1L << N` would silently
    // wrap once N >= 63 (Java masks the shift count to its low 6 bits), and (2) for moderate
    // indices, `base * 2^attempt` can overflow Long before we get a chance to cap. (1) is
    // handled by the early return; (2) by the rearranged inequality
    // `base > max / multiplier ⇔ base * multiplier > max`, which detects overflow without
    // actually overflowing.
    if (attempt >= 62) {
      return Duration.ofMillis(max);
    }
    long multiplier = 1L << Math.max(attempt, 0);
    long delay = (base > max / multiplier) ? max : base * multiplier;
    return Duration.ofMillis(delay);
  }

  private static boolean isRetriable(Throwable cause) {
    if (!(cause instanceof MarketDataException)) {
      // Conservative: unknown failure types don't get retried. The caller sees the original
      // exception rather than an amplified series of identical hits.
      return false;
    }
    if (cause instanceof NetworkError net) {
      // NetworkError wraps two shapes: actual transport failures (IOException + subtypes:
      // ConnectException, HttpTimeoutException, ...) and sync-throws from httpClient.sendAsync
      // (NPE, IllegalArgumentException — bugs, not network). Retry only the former; the latter
      // is deterministic and just burns the backoff for the same crash.
      return net.getCause() instanceof IOException;
    }
    if (cause instanceof ServerError server) {
      int status = server.getStatusCode();
      // Spec §9: 500 is not retriable; 501–599 are. The 0 sentinel comes from
      // ErrorContext.forNoResponse — a ServerError without a real HTTP code — and falls outside
      // the range, so the same check excludes it naturally.
      return status >= 501 && status <= 599;
    }
    // AuthenticationError, BadRequestError, RateLimitError, NotFoundError, ParseError: §9 says
    // never retry 4xx, and ParseError is deterministic.
    return false;
  }
}
