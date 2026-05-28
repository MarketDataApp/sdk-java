package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryAfterHeaderTest {

  private static final Instant NOW = Instant.parse("2026-05-18T12:00:00Z");

  // ---------- delta-seconds form ----------

  @Test
  void parsesPositiveSeconds() {
    assertThat(RetryAfterHeader.parse("120", NOW)).contains(Duration.ofSeconds(120));
  }

  @Test
  void parsesZeroSeconds() {
    assertThat(RetryAfterHeader.parse("0", NOW)).contains(Duration.ZERO);
  }

  @Test
  void negativeSecondsClampToZero() {
    // Spec doesn't allow negatives but clients have spotted them in the wild — treat as
    // "retry now" rather than blow up parsing.
    assertThat(RetryAfterHeader.parse("-5", NOW)).contains(Duration.ZERO);
  }

  @Test
  void valueIsTrimmedBeforeParsing() {
    assertThat(RetryAfterHeader.parse("  30  ", NOW)).contains(Duration.ofSeconds(30));
  }

  // ---------- HTTP-date form (RFC 1123) ----------

  @Test
  void parsesHttpDateInTheFuture() {
    // 5 minutes after NOW.
    String header = "Mon, 18 May 2026 12:05:00 GMT";
    assertThat(RetryAfterHeader.parse(header, NOW)).contains(Duration.ofMinutes(5));
  }

  @Test
  void httpDateInThePastClampsToZero() {
    // 10 seconds before NOW.
    String header = "Mon, 18 May 2026 11:59:50 GMT";
    assertThat(RetryAfterHeader.parse(header, NOW)).contains(Duration.ZERO);
  }

  // ---------- malformed ----------

  @Test
  void emptyHeaderProducesEmpty() {
    assertThat(RetryAfterHeader.parse("", NOW)).isEmpty();
    assertThat(RetryAfterHeader.parse("   ", NOW)).isEmpty();
  }

  @Test
  void garbageHeaderProducesEmpty() {
    // Neither a valid integer nor a parseable HTTP-date. Caller falls back to its own backoff.
    assertThat(RetryAfterHeader.parse("not-a-thing", NOW)).isEmpty();
    assertThat(RetryAfterHeader.parse("2026-05-18", NOW)).isEmpty(); // wrong date format
  }
}
