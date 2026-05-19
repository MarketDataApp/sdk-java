package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketDataLoggingTest {

  private static Logger sdkLogger() {
    return Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME);
  }

  @BeforeEach
  void reset() {
    MarketDataLogging.resetForTests();
  }

  @AfterEach
  void resetAfter() {
    MarketDataLogging.resetForTests();
  }

  // ---------- parseLevel ----------

  @Test
  void parseLevelMapsSpecVocabularyToJulLevels() {
    assertThat(MarketDataLogging.parseLevel("DEBUG")).isEqualTo(Level.FINE);
    assertThat(MarketDataLogging.parseLevel("INFO")).isEqualTo(Level.INFO);
    assertThat(MarketDataLogging.parseLevel("WARNING")).isEqualTo(Level.WARNING);
    assertThat(MarketDataLogging.parseLevel("ERROR")).isEqualTo(Level.SEVERE);
  }

  @Test
  void parseLevelIsCaseAndWhitespaceInsensitive() {
    assertThat(MarketDataLogging.parseLevel(" debug ")).isEqualTo(Level.FINE);
    assertThat(MarketDataLogging.parseLevel("Info")).isEqualTo(Level.INFO);
  }

  @Test
  void parseLevelFallsBackToDefaultWhenNullOrUnknown() {
    assertThat(MarketDataLogging.parseLevel(null)).isEqualTo(MarketDataLogging.DEFAULT_LEVEL);
    assertThat(MarketDataLogging.parseLevel("VERBOSE")).isEqualTo(MarketDataLogging.DEFAULT_LEVEL);
    assertThat(MarketDataLogging.parseLevel("")).isEqualTo(MarketDataLogging.DEFAULT_LEVEL);
  }

  // ---------- configure ----------

  @Test
  void configureInstallsExactlyOneHandlerWithTheCanonicalFormatter() {
    MarketDataLogging.configure("INFO");

    Handler[] handlers = sdkLogger().getHandlers();
    assertThat(handlers).hasSize(1);
    assertThat(handlers[0].getFormatter()).isInstanceOf(CanonicalLogFormatter.class);
  }

  @Test
  void configureSetsLevelOnSdkLogger() {
    MarketDataLogging.configure("DEBUG");
    assertThat(sdkLogger().getLevel()).isEqualTo(Level.FINE);
  }

  @Test
  void configureDisablesParentHandlersToAvoidDuplicateEmission() {
    MarketDataLogging.configure(null);
    assertThat(sdkLogger().getUseParentHandlers()).isFalse();
  }

  @Test
  void configureIsIdempotentAcrossCalls() {
    // Multiple MarketDataClient instances must not pile up handlers; the first call wins.
    MarketDataLogging.configure("DEBUG");
    MarketDataLogging.configure("ERROR");
    MarketDataLogging.configure("INFO");

    assertThat(sdkLogger().getHandlers()).hasSize(1);
    // First call's level stands (DEBUG → FINE), not the subsequent ones.
    assertThat(sdkLogger().getLevel()).isEqualTo(Level.FINE);
  }

  @Test
  void defaultLevelWhenSpecIsNullIsInfo() {
    MarketDataLogging.configure(null);
    assertThat(sdkLogger().getLevel()).isEqualTo(Level.INFO);
  }
}
