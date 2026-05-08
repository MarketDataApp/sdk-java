package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class MarketDataClientTest {

  @Test
  void buildsWithExplicitToken() {
    try (var client = new MarketDataClient("test-key", null, null, true)) {
      assertThat(client.isDemoMode()).isFalse();
      assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
    }
  }

  @Test
  void demoModeWhenNoTokenAvailable() {
    // Demo mode iff the full cascade (env var → .env → null) yields nothing. Deriving the
    // expectation from the same Configuration helper the constructor uses keeps the test
    // valid both on CI (no token anywhere → demoMode) and locally (.env-supplied token →
    // not demoMode); a plain `System.getenv` check would miss the .env source and break
    // locally.
    try (var client = new MarketDataClient()) {
      boolean expectDemo = Configuration.loadFromProcess().resolve(null, EnvVars.TOKEN) == null;
      assertThat(client.isDemoMode()).isEqualTo(expectDemo);
    }
  }

  @Test
  void fineLevelLoggingEmitsRedactedToken() {
    // The constructor logs the redacted token at FINE only. With the default logger
    // configuration (INFO), `LOG.isLoggable(FINE)` returns false and the line is dead from
    // JaCoCo's perspective. This test installs a capturing handler at FINE and asserts the
    // redacted token shows up — the unredacted token must not.
    Logger logger = Logger.getLogger(MarketDataClient.class.getName());
    Level previousLevel = logger.getLevel();
    boolean previousUseParent = logger.getUseParentHandlers();
    CapturingHandler capture = new CapturingHandler();
    logger.addHandler(capture);
    logger.setLevel(Level.FINE);
    logger.setUseParentHandlers(false);

    try (var client = new MarketDataClient("supersecret-token-VALUE-YKT0", null, null, false)) {
      assertThat(client.isDemoMode()).isFalse();
    } finally {
      logger.removeHandler(capture);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousUseParent);
    }

    assertThat(capture.records)
        .anySatisfy(
            r -> {
              assertThat(r.getLevel()).isEqualTo(Level.FINE);
              assertThat(r.getMessage()).contains("Token");
            });
    // Whatever was logged at FINE, the raw token must never appear in any record.
    for (LogRecord r : capture.records) {
      assertThat(r.getMessage() == null ? "" : r.getMessage())
          .doesNotContain("supersecret-token-VALUE-YKT0");
    }
  }

  /** Minimal {@link Handler} that buffers everything in memory for assertions. */
  private static final class CapturingHandler extends Handler {
    final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  void noArgConstructorAppliesProductionDefaults() {
    // The no-arg constructor must be equivalent to `new MarketDataClient(null, null, null,
    // true)` — production path with everything resolved from the cascade and startup
    // validation enabled. validateOnStartup and the userAgent format are env-independent,
    // so we assert them unconditionally; baseUrl/apiVersion fall back to the documented
    // defaults only when the cascade has no override, so we gate those assertions on the
    // env vars being unset (mirrors the demo-mode test above).
    try (var client = new MarketDataClient()) {
      assertThat(client.isValidateOnStartup()).isTrue();
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");

      if (System.getenv("MARKETDATA_BASE_URL") == null) {
        assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      }
      if (System.getenv("MARKETDATA_API_VERSION") == null) {
        assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
      }
    }
  }

  @Test
  void overridesAreHonored() {
    try (var client = new MarketDataClient("KEY", "https://example.test/", "v2", false)) {
      assertThat(client.getBaseUrl()).isEqualTo("https://example.test"); // trailing slash trimmed
      assertThat(client.getApiVersion()).isEqualTo("v2");
      assertThat(client.isValidateOnStartup()).isFalse();
    }
  }

  @Test
  void userAgentMatchesSpec() {
    try (var client = new MarketDataClient("KEY", null, null, true)) {
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");
    }
  }

  @Test
  void rateLimitsStartUnpopulated() {
    try (var client = new MarketDataClient("KEY", null, null, true)) {
      assertThat(client.getRateLimits()).isNull();
    }
  }
}
