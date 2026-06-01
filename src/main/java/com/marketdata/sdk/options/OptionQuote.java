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
 * <p>{@code rho} is part of the API schema but is an <em>optional</em> column — several feeds omit
 * it entirely or emit null cells. It is therefore the one greek typed as a nullable {@link Double};
 * {@code null} means "the response carried no rho for this contract", not zero.
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
    double iv,
    double delta,
    double gamma,
    double theta,
    double vega,
    @Nullable Double rho) {}
