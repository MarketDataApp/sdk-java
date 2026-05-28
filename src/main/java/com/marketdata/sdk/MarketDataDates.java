package com.marketdata.sdk;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Conventions for date/time fields surfaced to SDK consumers (§13.4).
 *
 * <p>Two distinct buckets in the codebase, intentional and worth documenting:
 *
 * <ul>
 *   <li><strong>Market-data timestamps</strong> — anything that comes from the wire and represents
 *       a moment in market time (quote times, candle bars, service uptime updates, etc.). Surfaced
 *       as {@link ZonedDateTime} in {@link #MARKET_ZONE} so the consumer sees the moment the way a
 *       trader thinks about it without converting. {@link #marketTimeFromEpochSecond} is the
 *       canonical conversion from the API's Unix-seconds wire format.
 *   <li><strong>SDK-internal timestamps</strong> — when the SDK constructed an error, when a log
 *       record was emitted, etc. These stay as {@link Instant} (zone-neutral) because they aren't
 *       market data and converting them to Eastern would imply a semantic they don't have. Display
 *       layers ({@code MarketDataException.getSupportInfo()}, {@link CanonicalLogFormatter}) render
 *       those internal timestamps in {@link #MARKET_ZONE} for presentation consistency, but the
 *       canonical value remains an {@code Instant}.
 * </ul>
 */
final class MarketDataDates {

  /** America/New_York — the zone the API uses for market hours and the SDK surfaces. */
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

  private MarketDataDates() {}

  /** Convert a Unix epoch-second timestamp (the API's wire format) to market-zone time. */
  static ZonedDateTime marketTimeFromEpochSecond(long epochSecond) {
    return Instant.ofEpochSecond(epochSecond).atZone(MARKET_ZONE);
  }
}
