package com.marketdata.sdk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Async-safe concurrency limiter. Replaces {@link java.util.concurrent.Semaphore} in the HTTP path
 * so that {@code executeAsync} never parks the caller's thread when the pool is at capacity — it
 * returns a {@link CompletableFuture} that completes when a permit is released by an in-flight
 * request. See ADR-007 for the rationale.
 *
 * <p>Two invariants:
 *
 * <ol>
 *   <li>Every permit is accounted for exactly once — it is either in {@link #availablePermits()}
 *       (free), held by an in-flight caller (and will be released via {@link #release()}), or
 *       pending in the waiter queue (and will be released by completing the waiter's future).
 *   <li>{@link CompletableFuture#complete} of a transferred permit always runs <em>outside</em> the
 *       lock. Completing a future runs the caller's attached callbacks synchronously on the
 *       releasing thread, and we never want those running while our lock is held.
 * </ol>
 *
 * <p>Cancelled or otherwise-completed waiters are skipped on {@link #release()} so a cancelled
 * {@code acquire} doesn't burn a permit.
 */
final class AsyncSemaphore {

  private final Object lock = new Object();
  private final Deque<CompletableFuture<Void>> waiters = new ArrayDeque<>();
  private int available;

  AsyncSemaphore(int permits) {
    if (permits < 0) {
      throw new IllegalArgumentException("permits must be >= 0, was " + permits);
    }
    this.available = permits;
  }

  /**
   * Asynchronously claim a permit.
   *
   * <p>Fast path: a permit is available, returns an already-completed future. Slow path: pool is
   * exhausted, returns a pending future enqueued FIFO; it completes when some in-flight caller
   * calls {@link #release()}. Either way, the caller's thread is never parked.
   */
  CompletableFuture<Void> acquire() {
    synchronized (lock) {
      if (available > 0) {
        available--;
        return CompletableFuture.completedFuture(null);
      }
      CompletableFuture<Void> waiter = new CompletableFuture<>();
      waiters.addLast(waiter);
      return waiter;
    }
  }

  /**
   * Release a permit. If a live waiter is enqueued, the permit is transferred to it (its future is
   * completed) without going through the counter. Otherwise the counter is incremented.
   */
  void release() {
    CompletableFuture<Void> next = null;
    synchronized (lock) {
      while (!waiters.isEmpty()) {
        CompletableFuture<Void> w = waiters.pollFirst();
        if (!w.isDone()) {
          next = w;
          break;
        }
      }
      if (next == null) {
        available++;
      }
    }
    if (next != null) {
      next.complete(null);
    }
  }

  /** Permits not currently held nor pending in the queue. */
  int availablePermits() {
    synchronized (lock) {
      return available;
    }
  }

  /** Number of pending waiters on the slow path. Useful for diagnostics and tests. */
  int queueLength() {
    synchronized (lock) {
      return waiters.size();
    }
  }
}
