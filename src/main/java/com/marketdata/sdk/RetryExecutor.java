package com.marketdata.sdk;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a {@link Supplier} of {@link CompletableFuture}s with retry-on-failure semantics governed
 * by {@link RetryPolicy}. Knows nothing about HTTP, JSON, or {@code MarketDataException} subtypes —
 * it just observes which causes the policy says are retriable and schedules subsequent attempts
 * after the policy's backoff.
 *
 * <p>Backoffs run on {@link CompletableFuture#delayedExecutor} so we don't own a scheduled-thread
 * pool that needs lifecycle management. The thread that runs each retry comes from {@code
 * ForkJoinPool.commonPool} after the delay elapses.
 *
 * <p>Cancellation of the outer result propagates to the in-flight attempt: if the caller cancels
 * mid-flight or mid-backoff, the next attempt is not scheduled and the current one (if any) is
 * cancelled.
 *
 * <p>Two supplier shapes are supported: {@link Supplier} for callers that don't need per-attempt
 * context, and {@link AttemptSupplier} for callers that need to inspect the previous attempt's
 * cause — used by {@link HttpTransport} to bypass the §10.3 preflight when retrying on an explicit
 * server-side {@code Retry-After} directive (§9.4).
 */
final class RetryExecutor {

  private final RetryPolicy policy;

  RetryExecutor(RetryPolicy policy) {
    this.policy = policy;
  }

  /** Visible to callers that need to compose their own retry predicate. */
  RetryPolicy policy() {
    return policy;
  }

  /**
   * Builds the future for one attempt. The {@code attemptIdx} starts at 0 for the first attempt;
   * {@code previousCause} is the (unwrapped) cause that triggered this retry, or {@code null} on
   * the first attempt.
   */
  @FunctionalInterface
  interface AttemptSupplier<T> {
    CompletableFuture<T> get(int attemptIdx, @Nullable Throwable previousCause);
  }

  /**
   * Drive {@code supplier} with retry. Each invocation of the supplier represents one attempt; if
   * the resulting future fails with a retriable cause, {@code supplier} is invoked again after the
   * policy-determined backoff. The retry decision uses the policy's own {@code shouldRetry}.
   */
  <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> supplier) {
    return execute(supplier, policy::shouldRetry);
  }

  /**
   * Like {@link #execute(Supplier)}, but the caller supplies a custom retry predicate. Used when an
   * external gate (e.g. the {@code /status/} pre-check from §9.5) needs to veto a retry the policy
   * would otherwise allow. {@link RetryPolicy#backoffDelay} still controls timing.
   */
  <T> CompletableFuture<T> execute(
      Supplier<CompletableFuture<T>> supplier, BiPredicate<Throwable, Integer> shouldRetry) {
    return execute((attemptIdx, previousCause) -> supplier.get(), shouldRetry);
  }

  /**
   * Like {@link #execute(Supplier, BiPredicate)} but the supplier receives the attempt index and
   * the previous attempt's cause so it can adjust behavior across retries — e.g. skip preflight
   * checks when the previous failure carried an explicit server-side {@code Retry-After}.
   */
  <T> CompletableFuture<T> execute(
      AttemptSupplier<T> supplier, BiPredicate<Throwable, Integer> shouldRetry) {
    CompletableFuture<T> result = new CompletableFuture<>();
    // One cancellation handler installed once: whichever attempt is currently in flight is
    // tracked in `currentAttempt`; cancelling `result` cancels that. Previous attempts are
    // already done by the time the next one overwrites the reference, so this avoids
    // accumulating one handler per attempt.
    AtomicReference<@Nullable CompletableFuture<T>> currentAttempt = new AtomicReference<>();
    result.whenComplete(
        (r, t) -> {
          if (t instanceof CancellationException) {
            CompletableFuture<T> inFlight = currentAttempt.get();
            if (inFlight != null && !inFlight.isDone()) {
              inFlight.cancel(false);
            }
          }
        });
    attempt(supplier, shouldRetry, 0, null, result, currentAttempt);
    return result;
  }

  private <T> void attempt(
      AttemptSupplier<T> supplier,
      BiPredicate<Throwable, Integer> shouldRetry,
      int attemptIdx,
      @Nullable Throwable previousCause,
      CompletableFuture<T> result,
      AtomicReference<@Nullable CompletableFuture<T>> currentAttempt) {
    if (result.isDone()) {
      // Caller cancelled (or completed exceptionally from a previous attempt's whenComplete).
      // Don't invoke the supplier again. Checking isDone() (not just isCancelled()) avoids
      // running a fresh attempt after the previous one's whenComplete completed `result`.
      return;
    }
    CompletableFuture<T> dispatched = supplier.get(attemptIdx, previousCause);
    currentAttempt.set(dispatched);

    // Race: `result.cancel(...)` may have fired between the isDone() check above and the
    // currentAttempt.set() call. The cancellation handler in execute() observes
    // currentAttempt under that race: if it sees the previous (already-done) attempt, it
    // doesn't cancel the new one. Re-check after publishing the new attempt.
    if (result.isCancelled() && !dispatched.isDone()) {
      dispatched.cancel(false);
      return;
    }

    dispatched.whenComplete(
        (value, error) -> {
          if (result.isDone()) {
            return;
          }
          if (error == null) {
            result.complete(value);
            return;
          }
          Throwable cause = unwrap(error);
          if (shouldRetry.test(cause, attemptIdx)) {
            long delayMs = policy.backoffDelay(cause, attemptIdx).toMillis();
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(
                    () ->
                        attempt(
                            supplier, shouldRetry, attemptIdx + 1, cause, result, currentAttempt));
          } else {
            result.completeExceptionally(cause);
          }
        });
  }

  // Package-private so the unwrap-when-nested-and-when-not branches are reachable from tests.
  static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }
}
