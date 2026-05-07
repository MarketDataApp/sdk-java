package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
