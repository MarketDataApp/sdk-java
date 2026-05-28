package com.marketdata.sdk;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Parses the HTTP {@code Retry-After} response header. Per RFC 7231 §7.1.3 the value is either:
 *
 * <ul>
 *   <li>delta-seconds — an unsigned integer like {@code 120}, OR
 *   <li>HTTP-date — an RFC 1123 timestamp like {@code Wed, 21 Oct 2025 07:28:00 GMT}.
 * </ul>
 *
 * <p>The parser accepts both forms. Past dates and negative seconds clamp to {@link Duration#ZERO}
 * ("retry immediately"). Malformed values yield {@link Optional#empty()}, which lets the caller
 * fall back to its calculated backoff per SDK requirements §9.4 ("respect server-specified delay"
 * is silent on malformed inputs).
 *
 * <p>This parser intentionally does <strong>not</strong> cap the result at any upper bound — the
 * spec says "override calculated backoff with server value", taking the server's directive at face
 * value. A future revision can introduce a cap if pathological values become an operational
 * concern.
 */
final class RetryAfterHeader {

  private RetryAfterHeader() {}

  /** Parse the header value against {@code now} (used only when the value is an HTTP-date). */
  static Optional<Duration> parse(String value, Instant now) {
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    Optional<Duration> asSeconds = parseSeconds(trimmed);
    if (asSeconds.isPresent()) {
      return asSeconds;
    }
    return parseHttpDate(trimmed, now);
  }

  private static Optional<Duration> parseSeconds(String value) {
    try {
      long seconds = Long.parseLong(value);
      // Negative deltas violate the spec but pop up in the wild; treat as "retry now".
      return Optional.of(Duration.ofSeconds(Math.max(0L, seconds)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Optional<Duration> parseHttpDate(String value, Instant now) {
    try {
      Instant target = DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from);
      long delaySeconds = ChronoUnit.SECONDS.between(now, target);
      return Optional.of(Duration.ofSeconds(Math.max(0L, delaySeconds)));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
