package com.marketdata.sdk.options;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Strikes available for a single expiration in an option chain — one row of {@link OptionsStrikes}.
 *
 * @param expiration the expiration date as a midnight {@code America/New_York} moment. Wire-format
 *     comes through as an ISO date-only string ({@code "2025-01-17"}) regardless of the §3 {@code
 *     dateformat} parameter — the strikes endpoint uses the key itself as the expiration label, so
 *     it cannot vary by format.
 * @param strikes strike prices for {@code expiration}, in ascending order as the API delivers them.
 *     Immutable; never {@code null}.
 */
public record ExpirationStrikes(ZonedDateTime expiration, List<Double> strikes) {

  public ExpirationStrikes {
    Objects.requireNonNull(expiration, "expiration");
    Objects.requireNonNull(strikes, "strikes");
    strikes = List.copyOf(strikes);
  }
}
