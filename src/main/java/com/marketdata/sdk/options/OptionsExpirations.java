package com.marketdata.sdk.options;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Response shape for {@code GET /v1/options/expirations/{underlying}/} — the available expiration
 * dates for the underlying's option chain.
 *
 * <p>Both fields are {@link ZonedDateTime} in {@code America/New_York} (§13.4 — the canonical
 * market zone). For expirations the time-of-day is always {@code 00:00} since the wire value is a
 * calendar date; the {@code ZonedDateTime} type is kept for consistency with the rest of the SDK
 * ({@code ServiceStatus.updated}, future {@code OptionQuote.expiration}, etc.) so consumers reach
 * for the same APIs regardless of which field they touch. Convert to {@link java.time.LocalDate}
 * via {@code .toLocalDate()} when the time-of-day is irrelevant.
 *
 * @param expirations the expiration dates (as midnight market-zone moments) in chronological order.
 *     Immutable; never {@code null}. Empty when the {@code "s":"no_data"} envelope is received.
 * @param updated when the server last refreshed this expirations list, in {@code America/New_York}.
 *     {@code null} only when the no-data envelope omits the {@code updated} field; the deserializer
 *     rejects any other absence with a {@link com.marketdata.sdk.exception.ParseError}.
 */
public record OptionsExpirations(
    List<ZonedDateTime> expirations, @org.jspecify.annotations.Nullable ZonedDateTime updated) {

  public OptionsExpirations {
    Objects.requireNonNull(expirations, "expirations");
    expirations = List.copyOf(expirations);
  }
}
