package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A single earnings report — one row of {@link StockEarnings}. Both historical reports and the
 * forward earnings calendar share this shape.
 *
 * <p>Several fields are legitimately {@code null}: {@code fiscalYear}/{@code fiscalQuarter} when
 * the provider lacks the fundamentals; {@code reportTime}/{@code reportDate} and the EPS figures on
 * a synthesized future-quarter row whose actuals don't exist yet. All fields are nullable boxed
 * types (also enabling {@code columns} projection).
 *
 * @param symbol the ticker.
 * @param fiscalYear fiscal year the report covers.
 * @param fiscalQuarter fiscal quarter (1–4).
 * @param date the fiscal period end date, lifted to a market-zone moment.
 * @param reportDate the date the report was (or will be) released.
 * @param reportTime relative release time, e.g. {@code "before open"} / {@code "after close"}.
 * @param currency reporting currency (ISO code).
 * @param reportedEPS actual earnings per share.
 * @param estimatedEPS consensus estimate.
 * @param surpriseEPS reported minus estimated.
 * @param surpriseEPSpct surprise as a fraction of the estimate.
 * @param updated row update timestamp in {@code America/New_York}.
 */
public record StockEarning(
    @Nullable String symbol,
    @Nullable Integer fiscalYear,
    @Nullable Integer fiscalQuarter,
    @Nullable ZonedDateTime date,
    @Nullable ZonedDateTime reportDate,
    @Nullable String reportTime,
    @Nullable String currency,
    @Nullable Double reportedEPS,
    @Nullable Double estimatedEPS,
    @Nullable Double surpriseEPS,
    @Nullable Double surpriseEPSpct,
    @Nullable ZonedDateTime updated) {}
