package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A single stock price — one row of {@link StockPrices}. A lighter-weight shape than {@link
 * StockQuote}: just the midpoint, the day's change, and the timestamp. Every field is a nullable
 * boxed type so {@code columns} can project the row and so a {@code NaN} from a closed/illiquid
 * market decodes to {@code null}.
 *
 * @param symbol the ticker.
 * @param mid the midpoint price.
 * @param change absolute change versus the prior close.
 * @param changepct fractional change versus the prior close.
 * @param updated price timestamp in {@code America/New_York}.
 */
public record StockPrice(
    @Nullable String symbol,
    @Nullable Double mid,
    @Nullable Double change,
    @Nullable Double changepct,
    @Nullable ZonedDateTime updated) {}
