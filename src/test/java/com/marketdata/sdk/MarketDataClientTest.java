package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.internal.Configuration;
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
    // No apiKey passed to the constructor. Demo mode iff the env/dotenv
    // cascade also yields nothing — true on any CI environment that
    // doesn't export MARKETDATA_TOKEN. This assertion is conditional
    // so the test stays valid in both cases.
    try (var client = new MarketDataClient()) {
      String envToken = System.getenv("MARKETDATA_TOKEN");
      boolean expectDemo = envToken == null || envToken.isBlank();
      assertThat(client.isDemoMode()).isEqualTo(expectDemo);
    }
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
