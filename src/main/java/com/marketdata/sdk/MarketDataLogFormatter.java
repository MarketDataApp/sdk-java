package com.marketdata.sdk;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * SDK requirements §7 log formatter. Produces lines shaped as {@code {timestamp} - {logger_name} -
 * {level} - {message}}, e.g.
 *
 * <pre>
 * 2026-05-12T13:42:18Z - com.marketdata.sdk.MarketDataClient - INFO - Initialized SDK 0.1.0
 * </pre>
 *
 * <p>Timestamps are in UTC ISO-8601 (second resolution) — the cross-language spec doesn't pick a
 * timezone, so the universal one wins. If the spec ever mandates US/Eastern for logs the way it
 * does for response dates, this is the only spot to change.
 */
final class MarketDataLogFormatter extends Formatter {

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

  @Override
  public String format(LogRecord record) {
    String timestamp = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.getMillis()));
    String name = record.getLoggerName() == null ? "(anonymous)" : record.getLoggerName();
    return timestamp
        + " - "
        + name
        + " - "
        + record.getLevel().getName()
        + " - "
        + formatMessage(record)
        + System.lineSeparator();
  }
}
