package com.marketdata.sdk.options;

import java.time.ZonedDateTime;

/**
 * A single end-of-day option quote — one row of {@link OptionsQuotes}. Carries the contract's
 * identification, market data (bid/ask/last/volume/open interest), in-the-money flag, intrinsic /
 * extrinsic decomposition, the underlying price the quote was struck against, and the standard set
 * of Black-Scholes greeks (delta, gamma, theta, vega) plus implied volatility.
 *
 * <p>Numeric size/count fields use {@code long} so a single record can carry post-Wall-Street-2.0
 * volume figures without silent truncation. Timestamps are {@link ZonedDateTime} in {@code
 * America/New_York}; their wire-format may be unix, ISO-string, or spreadsheet serial per the §3
 * {@code dateformat} parameter, all of which are decoded uniformly by the deserializer.
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
    double vega) {}
