package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MarketDataClient#configureLogging(Configuration)} in isolation. Driving it via a
 * synthetic {@link Configuration} avoids the JVM env-var dependency that would otherwise make these
 * tests flaky between CI and local runs.
 */
class MarketDataClientLoggingTest {

  private Logger sdkLogger;
  private Level previousLevel;
  private boolean previousUseParent;
  private Handler[] previousHandlers;

  @BeforeEach
  void snapshotLoggerState() {
    sdkLogger = Logger.getLogger(MarketDataClient.SDK_LOGGER_NAME);
    previousLevel = sdkLogger.getLevel();
    previousUseParent = sdkLogger.getUseParentHandlers();
    previousHandlers = sdkLogger.getHandlers().clone();
    // Strip any handlers the previous test (or process-level setup) might have installed.
    for (Handler h : previousHandlers) {
      sdkLogger.removeHandler(h);
    }
  }

  @AfterEach
  void restoreLoggerState() {
    // Drop anything configureLogging added during the test.
    for (Handler h : sdkLogger.getHandlers()) {
      sdkLogger.removeHandler(h);
    }
    sdkLogger.setLevel(previousLevel);
    sdkLogger.setUseParentHandlers(previousUseParent);
    for (Handler h : previousHandlers) {
      sdkLogger.addHandler(h);
    }
  }

  private static Configuration newConfig(Map<String, String> systemEnv) {
    try {
      Constructor<Configuration> ctor =
          Configuration.class.getDeclaredConstructor(Map.class, Map.class);
      ctor.setAccessible(true);
      return ctor.newInstance(systemEnv, Map.of());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- happy paths ----------

  @Test
  void appliesLevelFromEnvVar() {
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "FINE")));

    assertThat(sdkLogger.getLevel()).isEqualTo(Level.FINE);
    assertThat(Arrays.stream(sdkLogger.getHandlers()))
        .anyMatch(h -> h.getFormatter() instanceof MarketDataLogFormatter);
    assertThat(sdkLogger.getUseParentHandlers())
        .as("our handler bypasses parent so we don't double-emit with the JVM default formatter")
        .isFalse();
  }

  @Test
  void normalizesLevelCaseInsensitively() {
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "warning")));

    assertThat(sdkLogger.getLevel()).isEqualTo(Level.WARNING);
  }

  // ---------- idempotency ----------

  @Test
  void calledTwiceDoesNotDuplicateHandler() {
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "FINE")));
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "WARNING")));

    long sdkHandlers =
        Arrays.stream(sdkLogger.getHandlers())
            .filter(h -> h.getFormatter() instanceof MarketDataLogFormatter)
            .count();
    assertThat(sdkHandlers)
        .as("second call should refresh the level, not add a second handler")
        .isEqualTo(1);
    assertThat(sdkLogger.getLevel()).isEqualTo(Level.WARNING);
    // The handler's own level should track the latest call too.
    Arrays.stream(sdkLogger.getHandlers())
        .filter(h -> h.getFormatter() instanceof MarketDataLogFormatter)
        .forEach(h -> assertThat(h.getLevel()).isEqualTo(Level.WARNING));
  }

  // ---------- no-op paths ----------

  @Test
  void noOpWhenEnvVarUnset() {
    Level beforeLevel = sdkLogger.getLevel();
    int beforeHandlers = sdkLogger.getHandlers().length;

    MarketDataClient.configureLogging(newConfig(Map.of()));

    assertThat(sdkLogger.getLevel())
        .as("no env var means we don't touch the logger config")
        .isEqualTo(beforeLevel);
    assertThat(sdkLogger.getHandlers()).hasSize(beforeHandlers);
  }

  @Test
  void noOpWhenEnvVarIsBlank() {
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "   ")));

    assertThat(Arrays.stream(sdkLogger.getHandlers()))
        .noneMatch(h -> h.getFormatter() instanceof MarketDataLogFormatter);
  }

  @Test
  void noOpAndWarnsWhenEnvVarIsInvalid() {
    // Level.parse rejects unknown names; configureLogging swallows the IAE, logs a warning,
    // and leaves the logger untouched.
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "NOT_A_REAL_LEVEL")));

    assertThat(Arrays.stream(sdkLogger.getHandlers()))
        .as("invalid level must not install a handler — that would lie about the SDK's config")
        .noneMatch(h -> h.getFormatter() instanceof MarketDataLogFormatter);
  }
}
