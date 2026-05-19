package com.marketdata.sdk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
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
  private boolean closed;

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
   *
   * <p>After {@link #close()} every acquire fails immediately with {@link CancellationException};
   * waiters queued before the close were already drained with the same exception.
   */
  CompletableFuture<Void> acquire() {
    synchronized (lock) {
      if (closed) {
        return CompletableFuture.failedFuture(closedException());
      }
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
    // Outer loop handles the TOCTOU window between pollFirst (inside the lock) and
    // complete (outside): if the waiter is cancelled in that gap, complete(null) returns
    // false and the permit hasn't actually been transferred. Retry with the next waiter,
    // or fall through to the counter when the queue runs out of live waiters.
    while (true) {
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
          return;
        }
      }
      if (next.complete(null)) {
        return;
      }
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

  /**
   * Drain the waiter queue and reject future {@link #acquire()} calls. All currently-queued waiters
   * are completed exceptionally with {@link CancellationException} so the {@code thenCompose} chain
   * downstream of the dispatcher fails cleanly instead of leaving futures pending forever when the
   * owning client is closed mid-flight.
   *
   * <p>Idempotent: subsequent calls are no-ops. Permits already held by in-flight callers can still
   * be {@link #release()}d (the counter accepts it harmlessly) — this matters because cancellation
   * of a dispatched future cancels its permit, and that cancel-then-release path must continue to
   * work even after close.
   *
   * <p>Completion of drained waiters runs <em>outside</em> the lock for the same reason {@link
   * #release()} does it that way: completing a future runs callbacks synchronously, and we never
   * want those running with our lock held.
   */
  void close() {
    List<CompletableFuture<Void>> drained;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      drained = new ArrayList<>(waiters);
      waiters.clear();
    }
    for (CompletableFuture<Void> w : drained) {
      w.completeExceptionally(closedException());
    }
  }

  private static CancellationException closedException() {
    return new CancellationException("AsyncSemaphore is closed");
  }
}
