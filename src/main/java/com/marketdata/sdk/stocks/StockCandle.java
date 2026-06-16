package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A single OHLCV candle — one row of {@link StockCandles}. {@code time} is the bar's opening moment
 * in {@code America/New_York} (a daily bar is midnight market-time; an intraday bar carries the
 * time-of-day).
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
 * @param volume traded volume ({@code v}).
 */
public record StockCandle(
    @Nullable ZonedDateTime time,
    @Nullable Double open,
    @Nullable Double high,
    @Nullable Double low,
    @Nullable Double close,
    @Nullable Long volume) {}
