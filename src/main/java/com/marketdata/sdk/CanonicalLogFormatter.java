package com.marketdata.sdk;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * JUL {@link Formatter} producing the canonical SDK log format mandated by §9.1:
 *
 * <pre>{@code
 * {timestamp} - {logger_name} - {level} - {message}
 * }</pre>
 *
 * <p>Two normalizations matter:
 *
 * <ul>
 *   <li><strong>Timestamp</strong>: rendered in {@code America/New_York} with millisecond precision
 *       and the offset, matching the date-handling convention from §13.4. Looks like {@code
 *       2026-05-19T14:23:45.123-04:00}.
 *   <li><strong>Level</strong>: JUL's native level names ({@code FINE}, {@code SEVERE}) are mapped
 *       back to the spec's vocabulary ({@code DEBUG}, {@code ERROR}). Anything below {@link
 *       Level#FINE} also collapses to {@code DEBUG}; anything above {@link Level#SEVERE} to {@code
 *       ERROR}.
 * </ul>
 */
final class CanonicalLogFormatter extends Formatter {

  static final ZoneId ZONE = ZoneId.of("America/New_York");
  private static final DateTimeFormatter TS_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

  @Override
  public String format(LogRecord record) {
    String timestamp = TS_FORMAT.format(record.getInstant().atZone(ZONE));
    return timestamp
        + " - "
        + record.getLoggerName()
        + " - "
        + levelLabel(record.getLevel())
        + " - "
        + formatMessage(record)
        + System.lineSeparator();
  }

  static String levelLabel(Level level) {
    int n = level.intValue();
    if (n <= Level.FINE.intValue()) {
      return "DEBUG";
    }
    if (n < Level.WARNING.intValue()) {
      return "INFO";
    }
    if (n < Level.SEVERE.intValue()) {
      return "WARNING";
    }
    return "ERROR";
  }
}
