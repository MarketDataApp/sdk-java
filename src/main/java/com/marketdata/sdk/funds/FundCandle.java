package com.marketdata.sdk.funds;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A single OHLC candle — one row of {@link FundCandles}. {@code time} is the bar's opening moment
 * in {@code America/New_York} (always midnight market-time: funds have no intraday bars). Mutual
 * funds report NAV, not traded volume, so there is no {@code volume} column.
 *
 * <p>Every field is a nullable boxed type so the {@code columns} universal parameter can project
 * the response to a subset (an unrequested column decodes to {@code null}). The deserializer stays
 * strict about <em>requested</em> columns — a required column asked for but omitted by the API
 * surfaces as a {@code ParseError} (Option A), never a silent null.
 *
 * @param time bar timestamp ({@code t} on the wire).
 * @param open opening price ({@code o}).
 * @param high session high ({@code h}).
 * @param low session low ({@code l}).
 * @param close closing price ({@code c}).
 */
public record FundCandle(
    @Nullable ZonedDateTime time,
    @Nullable Double open,
    @Nullable Double high,
    @Nullable Double low,
    @Nullable Double close) {}
