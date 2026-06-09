package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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

  // Wire-format helpers for the three values of §3's universal `dateformat` parameter
  // (`unix`, `timestamp`, `spreadsheet`). The deserializer cannot ask the request "which one did
  // we send?", so it detects by JSON node shape. The numeric ranges are far enough apart that a
  // single threshold disambiguates safely:
  //   - spreadsheet serials for years 1900–2100 fit in roughly [1, 73000];
  //   - unix epochs for the same period are in [≈ -2.2e9, ≈ 4.1e9].
  // A threshold at 1_000_000 (~year 1970 + 11 days as a serial, ~Jan 12 1970 as an epoch) has no
  // realistic collision and we never need to refine it.
  private static final long UNIX_VS_SPREADSHEET_THRESHOLD = 1_000_000L;
  private static final LocalDate SPREADSHEET_EPOCH = LocalDate.of(1899, 12, 30);

  /**
   * Pattern for the timestamp-format datetime emitted by the API: {@code "2025-01-17 16:00:00
   * -05:00"} — space-separated, offset with explicit colon. Matches {@code
   * common/util/date_helper.py:format_date} in the backend.
   */
  private static final DateTimeFormatter ZONED_TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

  /**
   * Parse a date-field cell ({@code timestamp}, {@code unix}, or {@code spreadsheet}) to a calendar
   * {@link LocalDate}. {@code timestamp} strings are date-only ({@code "yyyy-MM-dd"}); numeric
   * values are either epoch seconds (interpreted in {@link #MARKET_ZONE}) or Excel serials
   * (whole-day part of days since 1899-12-30).
   */
  static LocalDate parseDateField(
      @org.jspecify.annotations.Nullable JsonParser p, JsonNode node, String fieldName)
      throws JsonMappingException {
    if (node == null || node.isNull()) {
      throw new JsonMappingException(p, "missing field: " + fieldName);
    }
    if (node.isTextual()) {
      try {
        return LocalDate.parse(node.asText());
      } catch (DateTimeParseException e) {
        throw new JsonMappingException(
            p, "non-ISO date string for field " + fieldName + ": " + node.asText());
      }
    }
    if (!node.isNumber()) {
      throw new JsonMappingException(p, "non-string, non-numeric date field: " + fieldName);
    }
    long whole = node.asLong();
    if (Math.abs(whole) >= UNIX_VS_SPREADSHEET_THRESHOLD) {
      return marketTimeFromEpochSecond(whole).toLocalDate();
    }
    return SPREADSHEET_EPOCH.plusDays(whole);
  }

  /**
   * Parse a cell that may carry <em>either</em> a date-only or a full-timestamp string (plus the
   * numeric {@code unix}/{@code spreadsheet} encodings) to a {@link ZonedDateTime} in {@link
   * #MARKET_ZONE}. Several stock endpoints flip between the two textual shapes under {@code
   * dateformat=timestamp}: a daily candle's {@code t} (and earnings/news date fields) come back
   * date-only ({@code "2026-06-03"}), while an intraday candle's {@code t} carries the full {@code
   * "yyyy-MM-dd HH:mm:ss XXX"}. This helper tolerates both — date-only strings are lifted to a
   * market-zone midnight — so a single model field decodes uniformly regardless of resolution.
   */
  static ZonedDateTime parseDateOrTimestampField(
      @org.jspecify.annotations.Nullable JsonParser p, JsonNode node, String fieldName)
      throws JsonMappingException {
    if (node == null || node.isNull()) {
      throw new JsonMappingException(p, "missing field: " + fieldName);
    }
    if (node.isTextual()) {
      String text = node.asText();
      try {
        return ZonedDateTime.parse(text, ZONED_TIMESTAMP_FORMAT).withZoneSameInstant(MARKET_ZONE);
      } catch (DateTimeParseException fullTimestamp) {
        try {
          return LocalDate.parse(text).atStartOfDay(MARKET_ZONE);
        } catch (DateTimeParseException dateOnly) {
          throw new JsonMappingException(
              p, "non-conforming date/timestamp string for field " + fieldName + ": " + text);
        }
      }
    }
    // Numeric encodings (unix epoch / spreadsheet serial) are unambiguous — defer to the shared
    // numeric path used by the pure-timestamp parser.
    return parseTimestampField(p, node, fieldName);
  }

  /**
   * Parse a timestamp-field cell ({@code timestamp}, {@code unix}, or {@code spreadsheet}) to a
   * {@link ZonedDateTime} in {@link #MARKET_ZONE}. {@code timestamp} strings include time-of-day
   * and offset ({@code "yyyy-MM-dd HH:mm:ss XXX"}); numeric values are epoch seconds or fractional
   * Excel serials.
   */
  static ZonedDateTime parseTimestampField(
      @org.jspecify.annotations.Nullable JsonParser p, JsonNode node, String fieldName)
      throws JsonMappingException {
    if (node == null || node.isNull()) {
      throw new JsonMappingException(p, "missing field: " + fieldName);
    }
    if (node.isTextual()) {
      try {
        return ZonedDateTime.parse(node.asText(), ZONED_TIMESTAMP_FORMAT)
            .withZoneSameInstant(MARKET_ZONE);
      } catch (DateTimeParseException e) {
        throw new JsonMappingException(
            p, "non-conforming timestamp string for field " + fieldName + ": " + node.asText());
      }
    }
    if (!node.isNumber()) {
      throw new JsonMappingException(p, "non-string, non-numeric timestamp field: " + fieldName);
    }
    double v = node.asDouble();
    if (Math.abs(v) >= UNIX_VS_SPREADSHEET_THRESHOLD) {
      return marketTimeFromEpochSecond((long) v);
    }
    // Spreadsheet serial: fractional days since 1899-12-30 UTC (the backend constructs it from a
    // UTC datetime, so the epoch reference is UTC, not Eastern).
    long millis = Math.round(v * 86_400_000d);
    Instant base = SPREADSHEET_EPOCH.atStartOfDay(ZoneOffset.UTC).toInstant();
    return base.plusMillis(millis).atZone(MARKET_ZONE);
  }
}
