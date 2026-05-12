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
        .anyMatch(h -> h instanceof MarketDataConsoleHandler);
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
            .filter(h -> h instanceof MarketDataConsoleHandler)
            .count();
    assertThat(sdkHandlers)
        .as("second call should refresh the level, not add a second handler")
        .isEqualTo(1);
    assertThat(sdkLogger.getLevel()).isEqualTo(Level.WARNING);
    // The handler's own level should track the latest call too.
    Arrays.stream(sdkLogger.getHandlers())
        .filter(h -> h instanceof MarketDataConsoleHandler)
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
        .noneMatch(h -> h instanceof MarketDataConsoleHandler);
  }

  // ---------- end-to-end: env var → real log emission with spec shape ----------

  /**
   * Closes the loop between {@link MarketDataClient#configureLogging} and {@link
   * MarketDataLogFormatter}. The other tests in this class verify the mechanics of configureLogging
   * (level applied, handler installed) and {@code MarketDataLogFormatterTest} verifies the
   * formatter shape in isolation — but neither alone catches a regression where the two stop
   * composing (e.g. configureLogging starts using a different formatter, or the level filter blocks
   * records the formatter would have rendered).
   *
   * <p>Strategy: let {@code configureLogging} install its handler, then add a parallel capturing
   * handler that reuses the same {@link MarketDataLogFormatter} so we observe the same line that
   * goes to stderr. Emit a record on a child of the SDK logger and assert the captured line matches
   * the spec shape exactly.
   */
  @Test
  void emittedRecordsAreFormattedPerSpec() {
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "FINE")));

    java.util.logging.Handler installed =
        Arrays.stream(sdkLogger.getHandlers())
            .filter(h -> h instanceof MarketDataConsoleHandler)
            .findFirst()
            .orElseThrow(() -> new AssertionError("configureLogging did not install its handler"));

    CapturingHandler capture = new CapturingHandler();
    capture.setFormatter(installed.getFormatter());
    capture.setLevel(Level.ALL);
    sdkLogger.addHandler(capture);

    Logger child = Logger.getLogger("com.marketdata.sdk.example");
    child.fine("hello world");

    assertThat(capture.formattedLines)
        .as("end-to-end logging must produce the spec-mandated shape")
        .anyMatch(
            line ->
                line.matches(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"
                        + " - com\\.marketdata\\.sdk\\.example - FINE - hello world"
                        + java.util.regex.Pattern.quote(System.lineSeparator())));
  }

  /**
   * Minimal {@link java.util.logging.Handler} that runs the configured formatter and stashes the
   * rendered string. Tests assert against {@link #formattedLines}; the raw records are not exposed
   * because the formatter is what we actually care about end-to-end.
   */
  private static final class CapturingHandler extends java.util.logging.Handler {
    final java.util.List<String> formattedLines = new java.util.ArrayList<>();

    @Override
    public void publish(java.util.logging.LogRecord record) {
      if (isLoggable(record)) {
        formattedLines.add(getFormatter().format(record));
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  void noOpAndWarnsWhenEnvVarIsInvalid() {
    // Level.parse rejects unknown names; configureLogging swallows the IAE, logs a warning,
    // and leaves the logger untouched.
    MarketDataClient.configureLogging(newConfig(Map.of(EnvVars.LOGGING_LEVEL, "NOT_A_REAL_LEVEL")));

    assertThat(Arrays.stream(sdkLogger.getHandlers()))
        .as("invalid level must not install a handler — that would lie about the SDK's config")
        .noneMatch(h -> h instanceof MarketDataConsoleHandler);
  }
}
