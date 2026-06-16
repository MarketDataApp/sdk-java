package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A single real-time stock quote — one row of {@link StockQuotes}. Carries the NBBO
 * (bid/ask/sizes), the mid/last, the day's change, and traded volume.
 *
 * <p>Every field is a nullable boxed type. Two reasons: the {@code columns} universal parameter can
 * project the row to a subset (unrequested columns decode to {@code null}); and the backend runs
 * {@code NaN → null} across the numeric columns, so a closed or illiquid market legitimately yields
 * {@code null} bid/ask/last even when those columns were requested.
 *
 * <p>The OHLC fields ({@link #open}/{@link #high}/{@link #low}/{@link #close}) are populated only
 * when the request opts in via {@code candle=true}; the 52-week extremes only when {@code
 * 52week=true}. Absent otherwise.
 *
 * @param symbol the ticker.
 * @param ask best ask price.
 * @param askSize best ask size.
 * @param bid best bid price.
 * @param bidSize best bid size.
 * @param mid midpoint of the NBBO.
 * @param last last traded price.
 * @param change absolute change versus the prior close.
 * @param changepct fractional change versus the prior close.
 * @param volume cumulative session volume.
 * @param updated quote timestamp in {@code America/New_York}.
 * @param open session open (opt-in via {@code candle}).
 * @param high session high (opt-in via {@code candle}).
 * @param low session low (opt-in via {@code candle}).
 * @param close session close (opt-in via {@code candle}).
 * @param week52High 52-week high (opt-in via {@code 52week}).
 * @param week52Low 52-week low (opt-in via {@code 52week}).
 */
public record StockQuote(
    @Nullable String symbol,
    @Nullable Double ask,
    @Nullable Long askSize,
    @Nullable Double bid,
    @Nullable Long bidSize,
    @Nullable Double mid,
    @Nullable Double last,
    @Nullable Double change,
    @Nullable Double changepct,
    @Nullable Long volume,
    @Nullable ZonedDateTime updated,
    @Nullable Double open,
    @Nullable Double high,
    @Nullable Double low,
    @Nullable Double close,
    @Nullable Double week52High,
    @Nullable Double week52Low) {}
