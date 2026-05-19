package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AsyncSemaphoreTest {

  // ---------- fast path ----------

  @Test
  void acquireReturnsCompletedFutureWhenPermitsAvailable() {
    AsyncSemaphore sem = new AsyncSemaphore(3);

    CompletableFuture<Void> a = sem.acquire();
    CompletableFuture<Void> b = sem.acquire();
    CompletableFuture<Void> c = sem.acquire();

    assertThat(a).isCompleted();
    assertThat(b).isCompleted();
    assertThat(c).isCompleted();
    assertThat(sem.availablePermits()).isZero();
    assertThat(sem.queueLength()).isZero();
  }

  // ---------- slow path ----------

  @Test
  void acquireReturnsPendingFutureWhenPoolExhausted() {
    AsyncSemaphore sem = new AsyncSemaphore(2);
    sem.acquire();
    sem.acquire();

    CompletableFuture<Void> waiter = sem.acquire();

    assertThat(waiter).isNotCompleted();
    assertThat(sem.availablePermits()).isZero();
    assertThat(sem.queueLength()).isOne();
  }

  @Test
  void releaseTransfersPermitDirectlyToFirstWaiter() {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire(); // pool empty

    CompletableFuture<Void> w1 = sem.acquire();
    CompletableFuture<Void> w2 = sem.acquire();

    sem.release();

    // The permit goes from the in-flight caller straight to w1 — never re-counted.
    assertThat(w1).isCompleted();
    assertThat(w2).isNotCompleted();
    assertThat(sem.availablePermits()).isZero();
    assertThat(sem.queueLength()).isOne();

    sem.release();

    assertThat(w2).isCompleted();
    assertThat(sem.availablePermits()).isZero();
    assertThat(sem.queueLength()).isZero();
  }

  @Test
  void releaseWithNoWaitersIncrementsCounter() {
    AsyncSemaphore sem = new AsyncSemaphore(2);
    sem.acquire();
    sem.acquire();

    sem.release();
    assertThat(sem.availablePermits()).isOne();

    sem.release();
    assertThat(sem.availablePermits()).isEqualTo(2);
  }

  // ---------- cancellation ----------

  @Test
  void cancelledWaiterIsSkippedOnRelease() {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire(); // pool empty

    CompletableFuture<Void> cancelled = sem.acquire();
    CompletableFuture<Void> alive = sem.acquire();
    cancelled.cancel(false);

    sem.release();

    // The cancelled waiter is skipped; the next live one gets the permit.
    assertThat(alive).isCompleted();
    assertThat(sem.queueLength()).isZero();
    assertThat(sem.availablePermits()).isZero();
  }

  @Test
  void releaseWhenAllWaitersCancelledFallsBackToCounter() {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire();

    sem.acquire().cancel(false);
    sem.acquire().cancel(false);

    sem.release();

    // No live waiter — the permit goes back to the pool.
    assertThat(sem.availablePermits()).isOne();
    assertThat(sem.queueLength()).isZero();
  }

  // ---------- ordering ----------

  @Test
  void waitersAreServedFifo() {
    AsyncSemaphore sem = new AsyncSemaphore(0);
    List<Integer> completionOrder = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      int id = i;
      sem.acquire().thenRun(() -> completionOrder.add(id));
    }

    for (int i = 0; i < 10; i++) {
      sem.release();
    }

    assertThat(completionOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  // ---------- race between release() and waiter cancellation ----------

  /**
   * Regression for the TOCTOU race in {@link AsyncSemaphore#release()} between {@code pollFirst()}
   * (inside the lock) and {@code complete(null)} (outside the lock). If the polled waiter is
   * cancelled in that window, {@code complete(null)} returns false and — without the retry loop —
   * the permit would be silently lost: it was already removed from the counter when release()
   * "transferred" it, and the cancelled waiter never delivers it anywhere.
   *
   * <p>The race is timing-sensitive; we coordinate two threads through a {@link CyclicBarrier} and
   * repeat the scenario many times so at least some iterations hit the bad window. The invariant we
   * assert is permit-conservation:
   *
   * <ul>
   *   <li>If the canceller won the race, the waiter is cancelled and {@code release()} must have
   *       found an alternative home for the permit — either the next live waiter, or the
   *       available-permits counter.
   *   <li>If the releaser won the race, the waiter completes normally and the counter stays at 0.
   * </ul>
   *
   * Either way, the permit is never lost.
   */
  @RepeatedTest(200)
  void releaseDoesNotLosePermitWhenWaiterIsCancelledMidRelease() throws Exception {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire(); // pool now empty

    CompletableFuture<Void> waiter = sem.acquire(); // queued

    CyclicBarrier barrier = new CyclicBarrier(2);

    Thread releaser =
        new Thread(
            () -> {
              awaitBarrier(barrier);
              sem.release();
            });
    Thread canceller =
        new Thread(
            () -> {
              awaitBarrier(barrier);
              waiter.cancel(false);
            });

    releaser.start();
    canceller.start();
    releaser.join();
    canceller.join();

    assertThat(sem.queueLength()).as("queue must be drained").isZero();

    if (waiter.isCancelled()) {
      // Canceller observed (or won) the race. Whatever release() did, the permit must have
      // landed somewhere — and with no other waiter present, that "somewhere" is the counter.
      assertThat(sem.availablePermits())
          .as("permit must return to the pool when the only waiter is cancelled")
          .isEqualTo(1);
    } else {
      // Releaser completed the waiter before cancel arrived. waiter must be done-normally,
      // and the permit is considered "held" by the (notional) downstream consumer of the waiter.
      assertThat(waiter)
          .as("if not cancelled, waiter must be completed normally")
          .isCompletedWithValue(null);
      assertThat(sem.availablePermits()).isZero();
    }
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      throw new AssertionError("barrier interrupted", e);
    }
  }

  // ---------- close ----------

  @Test
  void closeCompletesAllQueuedWaitersWithCancellation() {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire(); // pool empty

    CompletableFuture<Void> w1 = sem.acquire();
    CompletableFuture<Void> w2 = sem.acquire();
    CompletableFuture<Void> w3 = sem.acquire();

    sem.close();

    // CompletableFuture#join unwraps CancellationException specifically: it surfaces directly
    // rather than being wrapped in CompletionException. That's the same propagation downstream
    // observers see, so we assert the bare exception shape here.
    for (CompletableFuture<Void> w : List.of(w1, w2, w3)) {
      assertThat(w).isCompletedExceptionally();
      assertThatThrownBy(w::join)
          .isInstanceOf(CancellationException.class)
          .hasMessageContaining("closed");
    }
    assertThat(sem.queueLength()).isZero();
  }

  @Test
  void acquireAfterCloseReturnsFailedFutureImmediately() {
    AsyncSemaphore sem = new AsyncSemaphore(5);
    sem.close();

    CompletableFuture<Void> failed = sem.acquire();

    assertThat(failed).isCompletedExceptionally();
    assertThatThrownBy(failed::join)
        .isInstanceOf(CancellationException.class)
        .hasMessageContaining("closed");
    assertThat(sem.queueLength()).isZero();
  }

  @Test
  void closeIsIdempotent() {
    AsyncSemaphore sem = new AsyncSemaphore(1);
    CompletableFuture<Void> waiter = sem.acquire(); // takes the only permit
    CompletableFuture<Void> queued = sem.acquire();

    sem.close();
    sem.close(); // must be safe

    // First close completed the queued waiter; the second close has nothing to do.
    assertThat(queued).isCompletedExceptionally();
    // And the in-flight holder of the permit can still release without exploding.
    assertThat(waiter).isCompleted();
    sem.release();
  }

  @Test
  void releaseAfterCloseDoesNotExplode() {
    // After close the queue is empty, so release() falls through to the counter. Critical for
    // the cancel-permit-after-close path: HttpDispatcher cancels the permit when its dispatched
    // future is cancelled, and that cancellation may race close().
    AsyncSemaphore sem = new AsyncSemaphore(1);
    sem.acquire();
    sem.close();

    sem.release();
    assertThat(sem.availablePermits()).isOne();
  }

  // ---------- argument validation ----------

  @Test
  void rejectsNegativeInitialPermits() {
    assertThatThrownBy(() -> new AsyncSemaphore(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permits");
  }

  @Test
  void zeroInitialPermitsIsValidAndForcesSlowPath() {
    AsyncSemaphore sem = new AsyncSemaphore(0);

    CompletableFuture<Void> w = sem.acquire();
    assertThat(w).isNotCompleted();

    sem.release();
    assertThat(w).isCompleted();
  }
}
