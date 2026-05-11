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
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final RetryPolicy DEFAULTS = RetryPolicy.defaults();

  // ---------- shouldRetry: which errors are retriable ----------

  @Test
  void networkErrorsAreRetriable() {
    assertThat(DEFAULTS.shouldRetry(new NetworkError("boom", ErrorContext.empty()), 0)).isTrue();
  }

  @Test
  void status500IsNotRetriable() {
    ServerError err = new ServerError("500", new ErrorContext(null, "u", 500));
    assertThat(DEFAULTS.shouldRetry(err, 0)).isFalse();
  }

  @Test
  void status501Through599AreRetriable() {
    for (int code : new int[] {501, 502, 503, 504, 599}) {
      ServerError err = new ServerError("err", new ErrorContext(null, "u", code));
      assertThat(DEFAULTS.shouldRetry(err, 0)).as("status %d should be retriable", code).isTrue();
    }
  }

  @Test
  void authenticationErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new AuthenticationError("a", ErrorContext.empty()), 0))
        .isFalse();
  }

  @Test
  void badRequestErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new BadRequestError("b", ErrorContext.empty()), 0)).isFalse();
  }

  @Test
  void rateLimitErrorIsNotRetriable() {
    // Spec §9: "Never retry 4xx or rate limit errors." Even though 429 carries Retry-After in
    // some protocols, the SDK contract is to surface RateLimitError to the caller immediately.
    assertThat(DEFAULTS.shouldRetry(new RateLimitError("r", ErrorContext.empty()), 0)).isFalse();
  }

  @Test
  void notFoundErrorIsNotRetriable() {
    assertThat(DEFAULTS.shouldRetry(new NotFoundError("n", ErrorContext.empty()), 0)).isFalse();
  }

  @Test
  void parseErrorIsNotRetriable() {
    // A bad-shape body is deterministic — retrying produces the same broken decode.
    assertThat(DEFAULTS.shouldRetry(new ParseError("p", ErrorContext.empty()), 0)).isFalse();
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
    NetworkError retriable = new NetworkError("net", ErrorContext.empty());
    // Defaults: maxAttempts = 3 → only attempts 0 and 1 are eligible to be followed by a retry
    // (attempt 2 was the third try; no fourth attempt allowed).
    assertThat(DEFAULTS.shouldRetry(retriable, 0)).isTrue();
    assertThat(DEFAULTS.shouldRetry(retriable, 1)).isTrue();
    assertThat(DEFAULTS.shouldRetry(retriable, 2)).isFalse();
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

  // ---------- custom-tuned policy (used by tests that need fast retries) ----------

  @Test
  void customConstructorWiresValuesThrough() {
    RetryPolicy tiny =
        new RetryPolicy(/* maxAttempts */ 5, Duration.ofMillis(1), Duration.ofMillis(10));

    NetworkError net = new NetworkError("n", ErrorContext.empty());
    assertThat(tiny.shouldRetry(net, 3)).isTrue();
    assertThat(tiny.shouldRetry(net, 4)).isFalse();
    assertThat(tiny.backoffDelay(0)).isEqualTo(Duration.ofMillis(1));
    assertThat(tiny.backoffDelay(1)).isEqualTo(Duration.ofMillis(2));
    assertThat(tiny.backoffDelay(20)).isEqualTo(Duration.ofMillis(10));
  }
}
