package com.marketdata.sdk.markets;

import java.time.LocalDate;

/**
 * Whether the market was open on a single trading day.
 *
 * @param date the calendar date in the exchange's local time zone (US/Eastern for the default
 *     country US, per SDK requirements §11.4)
 * @param open {@code true} if the market session was open on that date, {@code false} if closed
 *     (weekend, holiday, etc.)
 */
public record DailyStatus(LocalDate date, boolean open) {}
