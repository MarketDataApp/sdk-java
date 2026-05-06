package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.internal.Configuration;
import org.junit.jupiter.api.Test;

class MarketDataClientTest {

  // All tests use validateOnStartup(false): with the default (true), the
  // client's constructor would call /user/ live, requiring a real token and
  // network access. Validation behavior itself is exercised in
  // UtilitiesResourceTest + MarketDataClientStartupValidationTest.

  @Test
  void buildsWithExplicitToken() {
    try (var client =
        MarketDataClient.builder().apiKey("test-key").validateOnStartup(false).build()) {
      assertThat(client.isDemoMode()).isFalse();
      assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
    }
  }

  @Test
  void demoModeWhenNoTokenAvailable() {
    // No apiKey set on the builder. Demo mode iff the *full* cascade — env var AND .env file —
    // yields no token. We ask Configuration directly for the truth instead of probing only
    // System.getenv, otherwise a local .env with a token would silently desync this assertion.
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      boolean cascadeHasToken =
          Configuration.loadFromProcess().resolve(null, "MARKETDATA_TOKEN") != null;
      assertThat(client.isDemoMode()).isEqualTo(!cascadeHasToken);
    }
  }

  @Test
  void overridesAreHonored() {
    try (var client =
        MarketDataClient.builder()
            .apiKey("KEY")
            .baseUrl("https://example.test/")
            .apiVersion("v2")
            .validateOnStartup(false)
            .build()) {
      assertThat(client.getBaseUrl()).isEqualTo("https://example.test"); // trailing slash trimmed
      assertThat(client.getApiVersion()).isEqualTo("v2");
      assertThat(client.isValidateOnStartup()).isFalse();
    }
  }

  @Test
  void userAgentMatchesSpec() {
    try (var client = MarketDataClient.builder().apiKey("KEY").validateOnStartup(false).build()) {
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");
    }
  }

  @Test
  void rateLimitsStartUnpopulated() {
    try (var client = MarketDataClient.builder().apiKey("KEY").validateOnStartup(false).build()) {
      assertThat(client.getRateLimits()).isNull();
    }
  }
}
