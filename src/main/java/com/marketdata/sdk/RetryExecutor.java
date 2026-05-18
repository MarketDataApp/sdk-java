package com.marketdata.sdk;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 */
final class RetryExecutor {

  private final RetryPolicy policy;

  RetryExecutor(RetryPolicy policy) {
    this.policy = policy;
  }

  /**
   * Drive {@code supplier} with retry. Each invocation of the supplier represents one attempt; if
   * the resulting future fails with a retriable cause, {@code supplier} is invoked again after the
   * policy-determined backoff.
   */
  <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> supplier) {
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
    attempt(supplier, 0, result, currentAttempt);
    return result;
  }

  private <T> void attempt(
      Supplier<CompletableFuture<T>> supplier,
      int attemptIdx,
      CompletableFuture<T> result,
      AtomicReference<@Nullable CompletableFuture<T>> currentAttempt) {
    if (result.isDone()) {
      // Caller cancelled (or completed exceptionally from a previous attempt's whenComplete).
      // Don't invoke the supplier again.
      return;
    }
    CompletableFuture<T> dispatched = supplier.get();
    currentAttempt.set(dispatched);

    // If the caller cancelled `result` between attempts (during a backoff window), the handler
    // installed in execute() has fired but `currentAttempt` was either null or pointing to
    // the previous (already-done) attempt — so the new attempt was never cancelled. Check
    // here and propagate immediately.
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
          if (policy.shouldRetry(cause, attemptIdx)) {
            long delayMs = policy.backoffDelay(attemptIdx).toMillis();
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> attempt(supplier, attemptIdx + 1, result, currentAttempt));
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
