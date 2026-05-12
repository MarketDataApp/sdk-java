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
    // validateOnStartup=false: this test verifies field-wiring on the explicit ctor, not the
    // /user/ probe. The probe path is exercised end-to-end against an in-process server in
    // MarketDataClientStartupValidationTest, and against the live API in MarketDataClientIT.
    try (var client = new MarketDataClient("test-key", null, null, false)) {
      assertThat(client.isDemoMode()).isFalse();
      assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
    }
  }

  @Test
  void demoModeWhenAllSourcesYieldNull() {
    // Demo mode iff the full cascade (apiKey → env var → .env → null) yields nothing. We use
    // the 4-arg ctor with validateOnStartup=false so the constructor never touches the network
    // — the assertion is purely about cascade resolution. assumeTrue gates the test on the
    // CI/local environment having no token; otherwise we couldn't reach demo mode without
    // mocking env vars from inside a unit test.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Configuration.loadFromProcess().resolve(null, EnvVars.TOKEN) == null,
        "MARKETDATA_TOKEN present in env — cannot exercise demo mode from a unit test");
    try (var client = new MarketDataClient(null, null, null, false)) {
      assertThat(client.isDemoMode()).isTrue();
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

  // `noArgConstructorAppliesProductionDefaults` (verifying the no-arg ctor end-to-end against
  // the production defaults) now lives in `src/integrationTest/.../MarketDataClientIT.java`,
  // because the post-§5 constructor hits /user/ when validateOnStartup=true and unit tests
  // must not depend on real network.

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
    // validateOnStartup=false so the userAgent assertion doesn't depend on a real /user/ call.
    try (var client = new MarketDataClient("KEY", null, null, false)) {
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");
    }
  }

  @Test
  void rateLimitsStartUnpopulated() {
    // validateOnStartup=false so the constructor does not hit /user/ and seed the snapshot;
    // this test asserts the pre-network state of the client.
    try (var client = new MarketDataClient("KEY", null, null, false)) {
      assertThat(client.getRateLimits()).isNull();
    }
  }
}
