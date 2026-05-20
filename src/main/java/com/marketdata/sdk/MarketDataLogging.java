package com.marketdata.sdk;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Global, idempotent JUL configuration for the SDK (§9). Installs one {@link ConsoleHandler} with
 * {@link CanonicalLogFormatter} on the SDK root logger ({@code com.marketdata.sdk}) and applies the
 * level resolved from the {@code MARKETDATA_LOGGING_LEVEL} env var (default {@code INFO}).
 *
 * <p>Sets {@code useParentHandlers=false} on the SDK logger so the canonical format is guaranteed —
 * the JDK's default root handler would otherwise re-emit every record with {@code
 * SimpleFormatter}'s shape, duplicating output.
 *
 * <p>Consumers that want to capture SDK logs into their own system (Logback, SLF4J bridges, file
 * appenders) should attach handlers directly to the {@code com.marketdata.sdk} logger. Their
 * handlers will see {@link java.util.logging.LogRecord} instances and can format / route them
 * however they like — the canonical formatter only applies to the handler this class installs.
 *
 * <p>The first {@link #configure(String)} call wins; subsequent calls are no-ops. This avoids
 * doubling handlers when multiple {@code MarketDataClient} instances are created in the same
 * process and avoids surprising config-flips when the second client passes a different level.
 *
 * <p><strong>Consumer-config detection</strong>: the SDK logger lives in a JVM-wide registry, so a
 * consumer (or another lib) may have already attached a handler or set a level on it before any
 * {@link MarketDataClient} is constructed. {@link #configure(String)} detects that and backs off
 * entirely — no handler added, no {@code useParentHandlers} flipped, no level overridden. This
 * makes the constructor's logging side-effect conditional: install the spec-default behavior only
 * when no other code has expressed an opinion.
 */
final class MarketDataLogging {

  static final String SDK_LOGGER_NAME = "com.marketdata.sdk";
  static final Level DEFAULT_LEVEL = Level.INFO;

  private static final AtomicBoolean configured = new AtomicBoolean(false);

  private MarketDataLogging() {}

  /**
   * Install the SDK's handler + formatter on the SDK root logger. Idempotent — first call wins;
   * subsequent calls are no-ops. Also backs off entirely when the SDK logger already carries a
   * handler or an explicit level (see class docs): the consumer has taken control, the SDK respects
   * it.
   *
   * @param levelSpec a level string from {@code MARKETDATA_LOGGING_LEVEL} ({@code DEBUG}, {@code
   *     INFO}, {@code WARNING}, {@code ERROR}, case-insensitive), or {@code null} for the default
   *     {@link #DEFAULT_LEVEL}.
   */
  static void configure(@Nullable String levelSpec) {
    if (!configured.compareAndSet(false, true)) {
      return;
    }
    Logger sdkLogger = Logger.getLogger(SDK_LOGGER_NAME);
    if (sdkLogger.getHandlers().length > 0 || sdkLogger.getLevel() != null) {
      // Consumer (or another library) already configured the SDK logger. Respect that
      // entirely: don't add our ConsoleHandler (would double-emit), don't flip
      // useParentHandlers (would break their parent-handler routing), don't overwrite the
      // level they explicitly chose.
      return;
    }
    Handler handler = new ConsoleHandler();
    handler.setFormatter(new CanonicalLogFormatter());
    // ConsoleHandler defaults its own filter to INFO; lower it so the logger's level is the
    // single source of truth for what gets emitted.
    handler.setLevel(Level.ALL);
    sdkLogger.addHandler(handler);
    sdkLogger.setUseParentHandlers(false);
    sdkLogger.setLevel(parseLevel(levelSpec));
  }

  static Level parseLevel(@Nullable String levelSpec) {
    if (levelSpec == null) {
      return DEFAULT_LEVEL;
    }
    return switch (levelSpec.trim().toUpperCase(Locale.ROOT)) {
      case "DEBUG" -> Level.FINE;
      case "INFO" -> Level.INFO;
      case "WARNING" -> Level.WARNING;
      case "ERROR" -> Level.SEVERE;
      default -> DEFAULT_LEVEL; // unknown spec → fall back to default rather than throw
    };
  }

  /**
   * Test-only seam: clear the installed handler and the idempotency flag so subsequent tests can
   * {@link #configure(String)} with different levels. Not part of the public contract; not
   * thread-safe.
   */
  static void resetForTests() {
    Logger sdkLogger = Logger.getLogger(SDK_LOGGER_NAME);
    for (Handler h : sdkLogger.getHandlers()) {
      sdkLogger.removeHandler(h);
    }
    sdkLogger.setUseParentHandlers(true);
    sdkLogger.setLevel(null);
    configured.set(false);
  }
}
