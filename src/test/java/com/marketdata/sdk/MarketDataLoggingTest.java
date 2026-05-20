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

  // ---------- consumer-config detection ----------

  @Test
  void configureSkipsWhenConsumerAlreadyAttachedAHandler() {
    // Consumer pre-attached their own handler (e.g. SLF4J bridge, Logback appender). The SDK
    // must not add its ConsoleHandler on top — that would emit each log line twice.
    Handler consumerHandler = new TestHandler();
    sdkLogger().addHandler(consumerHandler);

    MarketDataLogging.configure("DEBUG");

    assertThat(sdkLogger().getHandlers()).containsExactly(consumerHandler);
    // useParentHandlers must remain at its default (true) — flipping it would break the
    // consumer's parent-handler routing.
    assertThat(sdkLogger().getUseParentHandlers()).isTrue();
    // Level was not set by the SDK; remains null (inherits from parent).
    assertThat(sdkLogger().getLevel()).isNull();
  }

  @Test
  void configureSkipsWhenConsumerAlreadySetALevel() {
    // Consumer explicitly chose a level (e.g. FINE for local debugging). The SDK's default
    // INFO must not silently override it.
    sdkLogger().setLevel(Level.FINE);

    MarketDataLogging.configure("INFO");

    assertThat(sdkLogger().getLevel()).isEqualTo(Level.FINE);
    // No handler added either — having any opinion at all on the logger counts as "consumer
    // has taken control".
    assertThat(sdkLogger().getHandlers()).isEmpty();
  }

  @Test
  void configureRunsAgainAfterResetClearsConsumerState() {
    // Defensive: resetForTests() wipes both the idempotency flag and the logger state, so a
    // subsequent configure() must see a fresh slate and install the SDK defaults.
    sdkLogger().setLevel(Level.FINE); // simulate consumer state
    MarketDataLogging.configure("INFO");
    assertThat(sdkLogger().getHandlers()).isEmpty(); // skipped

    MarketDataLogging.resetForTests();
    MarketDataLogging.configure("INFO");

    assertThat(sdkLogger().getHandlers()).hasSize(1);
    assertThat(sdkLogger().getLevel()).isEqualTo(Level.INFO);
  }

  // ---------- §7 consolidation: every SDK class emits via the single root logger ----------

  /**
   * Regression guard for the consolidation: if anyone re-introduces a per-class logger via {@code
   * Logger.getLogger(SomeClass.class.getName())}, the configure() consumer-pre-config detection and
   * {@code useParentHandlers=false} guard would no longer cover the new sub-logger — records could
   * double-emit through the root JUL handler or escape the SDK's level control.
   */
  @Test
  void all_sdk_classes_emit_via_the_consolidated_root_logger() throws Exception {
    for (Class<?> clazz :
        java.util.List.of(
            MarketDataClient.class, HttpTransport.class, HttpDispatcher.class, StatusCache.class)) {
      java.lang.reflect.Field loggerField = clazz.getDeclaredField("LOGGER");
      loggerField.setAccessible(true);
      Logger logger = (Logger) loggerField.get(null);
      assertThat(logger.getName())
          .as(
              "Class %s must use Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME) so its records"
                  + " stay under the single configured root (§7).",
              clazz.getSimpleName())
          .isEqualTo(MarketDataLogging.SDK_LOGGER_NAME);
    }
  }

  /** Minimal Handler stub used to simulate a consumer-attached handler. */
  private static final class TestHandler extends Handler {
    @Override
    public void publish(java.util.logging.LogRecord record) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
