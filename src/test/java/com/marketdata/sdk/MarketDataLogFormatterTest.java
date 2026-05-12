package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class MarketDataLogFormatterTest {

  private final MarketDataLogFormatter formatter = new MarketDataLogFormatter();

  @Test
  void producesSpecShapedLine() {
    // SDK requirements §7: `{timestamp} - {logger_name} - {level} - {message}`.
    LogRecord record = new LogRecord(Level.INFO, "Initialized SDK");
    record.setLoggerName("com.marketdata.sdk.MarketDataClient");
    record.setMillis(1715000000000L); // 2024-05-06T12:53:20Z UTC

    String formatted = formatter.format(record);

    assertThat(formatted)
        .isEqualTo(
            "2024-05-06T12:53:20Z - com.marketdata.sdk.MarketDataClient - INFO - "
                + "Initialized SDK"
                + System.lineSeparator());
  }

  @Test
  void rendersMessageWithMessageFormatPlaceholders() {
    // java.util.logging supports `{0}`, `{1}`, ... placeholders. Our formatter delegates to
    // formatMessage so those get substituted correctly.
    LogRecord record = new LogRecord(Level.FINE, "Token: {0}");
    record.setLoggerName("com.marketdata.sdk.Tokens");
    record.setParameters(new Object[] {"***...***ABCD"});
    record.setMillis(1715000000000L);

    String formatted = formatter.format(record);

    assertThat(formatted).contains(" - FINE - Token: ***...***ABCD");
  }

  @Test
  void replacesNullLoggerNameWithPlaceholder() {
    // Anonymous Logger.getAnonymousLogger() records have a null name; rendering literal "null"
    // would be uglier than "(anonymous)".
    LogRecord record = new LogRecord(Level.WARNING, "no-name");
    record.setLoggerName(null);
    record.setMillis(1715000000000L);

    String formatted = formatter.format(record);

    assertThat(formatted).contains(" - (anonymous) - WARNING - no-name");
  }
}
