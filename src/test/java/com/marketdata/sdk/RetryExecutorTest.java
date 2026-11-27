package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.ServerError;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {

  // Sub-millisecond backoffs so tests don't wait on real wall-clock.
  private static final RetryPolicy FAST_RETRY =
      new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(2));

  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));

  private static ErrorContext ctx() {
    return ErrorContext.forNoResponse("https://example/u", Instant.EPOCH);
  }

  private static NetworkError retriableNet() {
    return new NetworkError("net", ctx(), new IOException("transport down"));
  }

  private static ServerError retriable5xx() {
    return new ServerError(
        "503", ErrorContext.forResponse("https://example/u", 503, null, Instant.EPOCH));
  }

  private static ServerError nonRetriable500() {
    return new ServerError(
        "500", ErrorContext.forResponse("https://example/u", 500, null, Instant.EPOCH));
  }

  // ---------- success on first attempt ----------

  @Test
  void firstAttemptSucceedsNoRetry() {
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    String result =
        exec.execute(
                () -> {
                  calls.incrementAndGet();
                  return CompletableFuture.completedFuture("ok");
                })
            .join();

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(1);
  }

  // ---------- retries until success ----------

  @Test
  void retriesUntilSuccess() {
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    String result =
        exec.execute(
                () -> {
                  int n = calls.incrementAndGet();
                  if (n < 3) {
                    return CompletableFuture.failedFuture(retriableNet());
                  }
                  return CompletableFuture.completedFuture("ok");
                })
            .join();

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
  }

  // ---------- exhausts attempts ----------

  @Test
  void exhaustsAttemptsAndSurfacesLastCause() {
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    CompletableFuture<String> f =
        exec.execute(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.failedFuture(retriable5xx());
            });

    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class);

    assertThat(calls).hasValue(4); // 1 initial + 3 retries
  }

  // ---------- noRetry policy: exactly one attempt regardless of cause ----------

  @Test
  void noRetryPolicyInvokesSupplierExactlyOnceOnFailure() {
    // RetryPolicy.noRetry() is the policy MarketDataClient#runStartupValidation uses to ensure
    // a slow/down API can't burn the full retry budget before the constructor returns. Verify
    // the supplier is invoked exactly once even for the most retriable failure shape.
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(RetryPolicy.noRetry());

    CompletableFuture<String> f =
        exec.execute(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.failedFuture(retriableNet());
            });

    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(NetworkError.class);

    assertThat(calls).hasValue(1);
  }

  // ---------- non-retriable surfaces immediately ----------

  @Test
  void nonRetriableCauseStopsImmediately() {
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    CompletableFuture<String> f =
        exec.execute(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.failedFuture(nonRetriable500());
            });

    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ServerError.class);

    assertThat(calls).hasValue(1);
  }

  // ---------- cancellation propagation ----------

  @Test
  void cancelOfResultCancelsInFlightAttempt() {
    AtomicReference<CompletableFuture<String>> handle = new AtomicReference<>();
    RetryExecutor exec = new RetryExecutor(NO_RETRY);

    CompletableFuture<String> result =
        exec.execute(
            () -> {
              CompletableFuture<String> f = new CompletableFuture<>();
              handle.set(f);
              return f;
            });

    assertThat(handle.get()).isNotNull();
    result.cancel(false);

    assertThat(handle.get()).isCancelled();
  }

  @Test
  void cancelDuringBackoffPreventsNextAttempt() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    // Slow enough backoff that we can cancel between attempts; the wall-clock cost is bounded
    // by the cancel firing before 50 ms elapses.
    RetryPolicy slow = new RetryPolicy(4, Duration.ofMillis(50), Duration.ofMillis(50));
    RetryExecutor exec = new RetryExecutor(slow);

    CompletableFuture<String> result =
        exec.execute(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.failedFuture(retriableNet());
            });

    // Wait until the first attempt has fired and we're presumably in the backoff window.
    Thread.sleep(10);
    result.cancel(false);

    // Give the (cancelled) scheduler a window to NOT fire a second attempt.
    Thread.sleep(150);

    assertThat(result).isCancelled();
    assertThat(calls.get()).as("should not have started a second attempt").isOne();
  }

  // ---------- result shape ----------

  // ---------- custom retry predicate overload ----------

  /**
   * The overload that accepts a custom {@code BiPredicate} is the seam HttpTransport uses to AND
   * the policy with a status-cache veto (§9.5). Verify that when the predicate returns false even
   * though the policy would have said true, no retry happens.
   */
  @Test
  void customPredicateCanVetoARetryThePolicyWouldHaveAllowed() {
    AtomicInteger calls = new AtomicInteger();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    CompletableFuture<String> f =
        exec.execute(
            () -> {
              calls.incrementAndGet();
              return CompletableFuture.failedFuture(retriableNet());
            },
            /* shouldRetry */ (cause, attempt) -> false);

    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(com.marketdata.sdk.exception.NetworkError.class);
    assertThat(calls).hasValue(1); // policy would have allowed; predicate vetoed
  }

  @Test
  void customPredicateReceivesUnwrappedCauseAndAttemptIndex() {
    java.util.List<Integer> seenAttempts = new java.util.ArrayList<>();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);

    exec.execute(
            () -> CompletableFuture.failedFuture(retriableNet()),
            (cause, attempt) -> {
              seenAttempts.add(attempt);
              // Allow first two retries, then veto.
              return attempt < 2;
            })
        .exceptionally(e -> null)
        .join();

    assertThat(seenAttempts).containsExactly(0, 1, 2);
  }

  // ---------- context-aware supplier threading previousCause ----------

  @Test
  void attemptSupplierReceivesAttemptIndexAndPreviousCause() {
    // The AttemptSupplier variant exposes the previous attempt's (unwrapped) cause so callers
    // can branch — e.g. HttpTransport bypasses preflight when previousCause carries an explicit
    // server-side Retry-After. This test pins that the threading is correct across attempts.
    java.util.List<Integer> seenAttempts = new java.util.ArrayList<>();
    java.util.List<Throwable> seenCauses = new java.util.ArrayList<>();
    RetryExecutor exec = new RetryExecutor(FAST_RETRY);
    NetworkError netError = retriableNet();

    exec.execute(
            (attemptIdx, previousCause) -> {
              seenAttempts.add(attemptIdx);
              seenCauses.add(previousCause);
              return CompletableFuture.failedFuture(netError);
            },
            (cause, attempt) -> attempt < 2)
        .exceptionally(e -> null)
        .join();

    assertThat(seenAttempts).containsExactly(0, 1, 2);
    // First attempt has no previous cause; subsequent attempts see the unwrapped NetworkError.
    assertThat(seenCauses.get(0)).isNull();
    assertThat(seenCauses.get(1)).isSameAs(netError);
    assertThat(seenCauses.get(2)).isSameAs(netError);
  }

  @Test
  void resultFutureCarriesCancellationException() {
    RetryExecutor exec = new RetryExecutor(NO_RETRY);

    CompletableFuture<String> result =
        exec.execute(CompletableFuture::new); // never-completing supplier

    result.cancel(false);

    assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
  }
}
