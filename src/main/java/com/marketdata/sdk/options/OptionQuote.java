package com.marketdata.sdk.options;

import java.time.ZonedDateTime;
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
 * <p>The model-derived values — implied volatility and the Black-Scholes greeks ({@code iv}, {@code
 * delta}, {@code gamma}, {@code theta}, {@code vega}, {@code rho}) — are typed as nullable {@link
 * Double}. On historical or illiquid rows the API legitimately returns {@code null} for them (no
 * model output that day); {@code null} therefore means "not provided for this contract/row", not
 * zero. The market-data fields (bid/ask/last/volume/…) stay primitive — they are always present.
 */
public record OptionQuote(
    String optionSymbol,
    String underlying,
    ZonedDateTime expiration,
    String side,
    double strike,
    ZonedDateTime firstTraded,
    int dte,
    ZonedDateTime updated,
    double bid,
    long bidSize,
    double mid,
    double ask,
    long askSize,
    double last,
    long openInterest,
    long volume,
    boolean inTheMoney,
    double intrinsicValue,
    double extrinsicValue,
    double underlyingPrice,
    @Nullable Double iv,
    @Nullable Double delta,
    @Nullable Double gamma,
    @Nullable Double theta,
    @Nullable Double vega,
    @Nullable Double rho) {}
