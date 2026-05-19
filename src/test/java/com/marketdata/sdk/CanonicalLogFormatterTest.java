package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class CanonicalLogFormatterTest {

  private static LogRecord recordAt(Level level, String logger, String message, Instant when) {
    LogRecord r = new LogRecord(level, message);
    r.setLoggerName(logger);
    r.setInstant(when);
    return r;
  }

  @Test
  void formatProducesCanonicalShape() {
    CanonicalLogFormatter fmt = new CanonicalLogFormatter();
    LogRecord r =
        recordAt(
            Level.INFO,
            "com.marketdata.sdk.HttpTransport",
            "Sending GET to https://api/v1/markets/status/",
            Instant.parse("2026-05-19T18:00:00Z"));

    String out = fmt.format(r);

    // {timestamp} - {logger_name} - {level} - {message}\n
    String[] parts = out.split(" - ", 4);
    assertThat(parts).hasSize(4);
    assertThat(parts[1]).isEqualTo("com.marketdata.sdk.HttpTransport");
    assertThat(parts[2]).isEqualTo("INFO");
    assertThat(parts[3]).startsWith("Sending GET to https://api/v1/markets/status/");
    assertThat(out).endsWith(System.lineSeparator());
  }

  @Test
  void timestampIsRenderedInEasternZone() {
    CanonicalLogFormatter fmt = new CanonicalLogFormatter();
    // 18:00 UTC → 14:00 Eastern (EDT in May).
    Instant when = Instant.parse("2026-05-19T18:00:00Z");
    LogRecord r = recordAt(Level.INFO, "logger", "msg", when);

    String out = fmt.format(r);
    String timestamp = out.substring(0, out.indexOf(" - "));

    ZonedDateTime parsed = ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    assertThat(parsed.toInstant()).isEqualTo(when);
    // Eastern in May is UTC-04:00 (DST).
    assertThat(timestamp).endsWith("-04:00");
    assertThat(timestamp).startsWith("2026-05-19T14:00:00.000");
  }

  @Test
  void julLevelsMapToSpecVocabulary() {
    assertThat(CanonicalLogFormatter.levelLabel(Level.FINEST)).isEqualTo("DEBUG");
    assertThat(CanonicalLogFormatter.levelLabel(Level.FINE)).isEqualTo("DEBUG");
    assertThat(CanonicalLogFormatter.levelLabel(Level.CONFIG)).isEqualTo("INFO");
    assertThat(CanonicalLogFormatter.levelLabel(Level.INFO)).isEqualTo("INFO");
    assertThat(CanonicalLogFormatter.levelLabel(Level.WARNING)).isEqualTo("WARNING");
    assertThat(CanonicalLogFormatter.levelLabel(Level.SEVERE)).isEqualTo("ERROR");
  }

  @Test
  void allFourSpecLevelsRoundTripThroughTheFormatter() {
    CanonicalLogFormatter fmt = new CanonicalLogFormatter();
    Instant now = Instant.now();
    assertThat(fmt.format(recordAt(Level.FINE, "x", "m", now))).contains(" - DEBUG - ");
    assertThat(fmt.format(recordAt(Level.INFO, "x", "m", now))).contains(" - INFO - ");
    assertThat(fmt.format(recordAt(Level.WARNING, "x", "m", now))).contains(" - WARNING - ");
    assertThat(fmt.format(recordAt(Level.SEVERE, "x", "m", now))).contains(" - ERROR - ");
  }
}
