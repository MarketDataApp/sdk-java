package com.marketdata.sdk.options;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A single end-of-day option quote — one row of {@link OptionsQuotes}. Carries the contract's
 * identification, market data (bid/ask/last/volume/open interest), in-the-money flag, intrinsic /
 * extrinsic decomposition, the underlying price the quote was struck against, and the standard set
 * of Black-Scholes greeks (delta, gamma, theta, vega, rho) plus implied volatility.
 *
 * <p>Numeric size/count fields use {@code long} so a single record can carry post-Wall-Street-2.0
 * volume figures without silent truncation. Timestamps are {@link ZonedDateTime} in {@code
 * America/New_York}; their wire-format may be unix, ISO-string, or spreadsheet serial per the §3
 * {@code dateformat} parameter, all of which are decoded uniformly by the deserializer.
 *
 * <p>Every field is a nullable boxed type. This is what lets the {@code columns} universal
 * parameter project the response to a subset of fields: a column the consumer did not request comes
 * back {@code null}. The deserializer is still strict about <em>requested</em> fields — a required
 * field that was asked for but is missing surfaces as a {@code ParseError} (Option A), never a
 * silent null — so a {@code null} here means either "not requested (projected away)" or, for the
 * model-derived values ({@code iv} and the greeks), "legitimately not provided for this row".
 */
public record OptionQuote(
    @Nullable String optionSymbol,
    @Nullable String underlying,
    @Nullable ZonedDateTime expiration,
    @Nullable String side,
    @Nullable Double strike,
    @Nullable ZonedDateTime firstTraded,
    @Nullable Integer dte,
    @Nullable ZonedDateTime updated,
    @Nullable Double bid,
    @Nullable Long bidSize,
    @Nullable Double mid,
    @Nullable Double ask,
    @Nullable Long askSize,
    @Nullable Double last,
    @Nullable Long openInterest,
    @Nullable Long volume,
    @Nullable Boolean inTheMoney,
    @Nullable Double intrinsicValue,
    @Nullable Double extrinsicValue,
    @Nullable Double underlyingPrice,
    @Nullable Double iv,
    @Nullable Double delta,
    @Nullable Double gamma,
    @Nullable Double theta,
    @Nullable Double vega,
    @Nullable Double rho) {

  /**
   * The greeks present (non-null) on this row, as an immutable set. Empty when none were computed
   * (e.g. an illiquid contract whose implied volatility couldn't be solved). Note {@code rho} is
   * often absent even when the rest are present.
   */
  public Set<Greek> presentGreeks() {
    EnumSet<Greek> s = EnumSet.noneOf(Greek.class);
    if (delta != null) {
      s.add(Greek.DELTA);
    }
    if (gamma != null) {
      s.add(Greek.GAMMA);
    }
    if (theta != null) {
      s.add(Greek.THETA);
    }
    if (vega != null) {
      s.add(Greek.VEGA);
    }
    if (rho != null) {
      s.add(Greek.RHO);
    }
    return Collections.unmodifiableSet(s);
  }

  /** The value of a given greek on this row, or {@code null} when that greek is absent. */
  public @Nullable Double greek(Greek g) {
    return switch (g) {
      case DELTA -> delta;
      case GAMMA -> gamma;
      case THETA -> theta;
      case VEGA -> vega;
      case RHO -> rho;
    };
  }
}
