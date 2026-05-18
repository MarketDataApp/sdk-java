package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final RetryPolicy DEFAULTS = RetryPolicy.defaults();

  private static ErrorContext ctxNoResponse() {
    return ErrorContext.forNoResponse("https://example/u", Instant.EPOCH);
  }

  private static ErrorContext ctxWithStatus(int status) {
    return ErrorContext.forResponse("https://example/u", status, null, Instant.EPOCH);
  }

  // ---------- shouldRetry: which errors are retriable ----------

  @Test
  void networkErrorsWithIoCauseAreRetriable() {
    // The canonical "real network failure" shape: NetworkError wraps an IOException (or
    // subtype like ConnectException / HttpTimeoutException).
    NetworkError err =
        new NetworkError(
            "connect refused", ctxNoResponse(), new java.io.IOException("connect refused"));
    assertThat(DEFAULTS.shouldRetry(err, 0)).isTrue();
  }

  @Test
  void networkErrorsWithoutCauseAreNotRetriable() {
    // A NetworkError with no cause has no signal that it was an actual network failure.
    // Better to surface immediately than burn 3 attempts on a possibly-deterministic bug.
    assertThat(DEFAULTS.shouldRetry(new NetworkError("boom", ctxNoResponse()), 0)).isFalse();
  }

  @Test
  void networkErrorsWrappingNonIoCauseAreNotRetriable() {
    // Sync-throws from httpClient.sendAsync() (malformed request, internal NPE,
    // IllegalArgumentException, etc.) get wrapped as NetworkError in `dispatch`, but they're
    // deterministic — retrying just wastes the 1s+2s backoff for the same crash.
    NetworkError syncThrow =
        new NetworkError(
            "Request failed before dispatch",
            ctxNoResponse(),
            new IllegalArgumentException("malformed URI"));
    assertThat(DEFAULTS.shouldRetry(syncThrow, 0)).isFalse();
  }

  @Test
  void status500IsNotRetriable() {
    ServerError err = new ServerError("500", ctxWithStatus(500));
    assertThat(DEFAULTS.shouldRetry(err, 0)).isFalse();
  }

  @Test
  void status501Through599AreRetriable() {
    for (int code : new int[] {501, 502, 503, 504, 599}) {
      ServerError err = new ServerError("err", ctxWithStatus(code));
      assertThat(DEFAULTS.shouldRetry(err, 0)).as("status %d should be retriable", code).isTrue();
    }
  }

  @Test
  void serverErrorWithoutHttpStatusIsNotRetriable() {
    // ErrorContext.forNoResponse sets statusCode = 0 — the synthetic-path sentinel for a
    // ServerError that wasn't backed by a real HTTP response. Falls outside 501–599, so it's
    // not retried.
    ServerError synthetic = new ServerError("no response", ctxNoResponse());
    assertThat(DEFAULTS.shouldRetry(synthetic, 0)).isFalse();
  }

  @Test
  void authenticationErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new AuthenticationError("a", ctxWithStatus(401)), 0)).isFalse();
  }

  @Test
  void badRequestErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new BadRequestError("b", ctxWithStatus(400)), 0)).isFalse();
  }

  @Test
  void rateLimitErrorIsNotRetriable() {
    // Spec §9: "Never retry 4xx or rate limit errors." Even though 429 carries Retry-After in
    // some protocols, the SDK contract is to surface RateLimitError to the caller immediately.
    assertThat(DEFAULTS.shouldRetry(new RateLimitError("r", ctxWithStatus(429)), 0)).isFalse();
  }

  @Test
  void notFoundErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new NotFoundError("n", ctxWithStatus(404)), 0)).isFalse();
  }

  @Test
  void parseErrorIsNotRetriable() {
    // A bad-shape body is deterministic — retrying produces the same broken decode.
    assertThat(DEFAULTS.shouldRetry(new ParseError("p", ctxNoResponse()), 0)).isFalse();
  }

  @Test
  void unknownThrowableIsNotRetriable() {
    // Conservative default for non-MarketDataException causes: don't retry. Better to surface
    // the unknown failure than to silently hammer the API.
    assertThat(DEFAULTS.shouldRetry(new RuntimeException("?"), 0)).isFalse();
  }

  // ---------- shouldRetry: respect max attempts ----------

  @Test
  void retriesStopAfterMaxAttempts() {
    NetworkError retriable =
        new NetworkError("net", ctxNoResponse(), new java.io.IOException("transport down"));
    // Defaults: maxAttempts = 4 → attempts 0, 1, 2 are eligible to be followed by a retry
    // (attempt 3 was the fourth try; no fifth attempt allowed).
    assertThat(DEFAULTS.shouldRetry(retriable, 0)).isTrue();
    assertThat(DEFAULTS.shouldRetry(retriable, 1)).isTrue();
    assertThat(DEFAULTS.shouldRetry(retriable, 2)).isTrue();
    assertThat(DEFAULTS.shouldRetry(retriable, 3)).isFalse();
    assertThat(DEFAULTS.shouldRetry(retriable, 99)).isFalse();
  }

  // ---------- backoffDelay: exponential with cap ----------

  @Test
  void backoffStartsAtInitialAndDoubles() {
    assertThat(DEFAULTS.backoffDelay(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(DEFAULTS.backoffDelay(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(DEFAULTS.backoffDelay(2)).isEqualTo(Duration.ofSeconds(4));
    assertThat(DEFAULTS.backoffDelay(3)).isEqualTo(Duration.ofSeconds(8));
  }

  @Test
  void backoffCapsAtMaxBackoff() {
    // 2^5 = 32 > 30 cap; 2^10 way over.
    assertThat(DEFAULTS.backoffDelay(5)).isEqualTo(Duration.ofSeconds(30));
    assertThat(DEFAULTS.backoffDelay(10)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void backoffSaturatesOnExtremeAttemptIndices() {
    // The shift `1L << attempt` is undefined for attempt >= 63 (the shift count is masked to
    // the bottom 6 bits, wrapping silently); the implementation guards against this by
    // capping at maxBackoff once the multiplier would overflow.
    assertThat(DEFAULTS.backoffDelay(62)).isEqualTo(Duration.ofSeconds(30));
    assertThat(DEFAULTS.backoffDelay(70)).isEqualTo(Duration.ofSeconds(30));
    assertThat(DEFAULTS.backoffDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
  }

  // ---------- custom-tuned policy (used by tests that need fast retries) ----------

  @Test
  void customConstructorWiresValuesThrough() {
    RetryPolicy tiny =
        new RetryPolicy(/* maxAttempts */ 5, Duration.ofMillis(1), Duration.ofMillis(10));

    NetworkError net =
        new NetworkError("n", ctxNoResponse(), new java.io.IOException("transport down"));
    assertThat(tiny.shouldRetry(net, 3)).isTrue();
    assertThat(tiny.shouldRetry(net, 4)).isFalse();
    assertThat(tiny.backoffDelay(0)).isEqualTo(Duration.ofMillis(1));
    assertThat(tiny.backoffDelay(1)).isEqualTo(Duration.ofMillis(2));
    assertThat(tiny.backoffDelay(20)).isEqualTo(Duration.ofMillis(10));
  }

  // ---------- backoffDelay(cause, attempt) honors Retry-After ----------

  @Test
  void backoffWithCauseFallsBackToExponentialWhenCauseHasNoRetryAfter() {
    // ServerError without Retry-After → exponential as before.
    ServerError noRetryAfter = new ServerError("503", ctxWithStatus(503));
    assertThat(DEFAULTS.backoffDelay(noRetryAfter, 0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(DEFAULTS.backoffDelay(noRetryAfter, 3)).isEqualTo(Duration.ofSeconds(8));
  }

  @Test
  void backoffWithCauseHonorsRetryAfterOnServerError() {
    // The server's Retry-After completely replaces the calculated exponential — even when the
    // exponential would have been smaller (server knows better).
    ServerError withRetryAfter =
        new ServerError(
            "503", ctxWithStatus(503), /* cause */ null, /* retryAfter */ Duration.ofSeconds(45));

    // Attempt 0 would normally be 1s; Retry-After overrides to 45s.
    assertThat(DEFAULTS.backoffDelay(withRetryAfter, 0)).isEqualTo(Duration.ofSeconds(45));
    // Attempt 5 would normally cap at 30s; Retry-After still wins with 45s.
    assertThat(DEFAULTS.backoffDelay(withRetryAfter, 5)).isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void backoffWithCauseIgnoresRetryAfterOnNonServerErrorCauses() {
    // NetworkError doesn't carry Retry-After at all → exponential math.
    NetworkError net = new NetworkError("n", ctxNoResponse(), new IOException("down"));
    assertThat(DEFAULTS.backoffDelay(net, 1)).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void rejectsNonPositiveMaxAttempts() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new RetryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAttempts");
  }
}
