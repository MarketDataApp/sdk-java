package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.internal.Configuration;
import org.junit.jupiter.api.Test;

class MarketDataClientTest {

  @Test
  void buildsWithExplicitToken() {
    try (var client = MarketDataClient.builder().apiKey("test-key").build()) {
      assertThat(client.isDemoMode()).isFalse();
      assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
    }
  }

  @Test
  void demoModeWhenNoTokenAvailable() {
    // No apiKey set on the builder. Demo mode iff the env/dotenv
    // cascade also yields nothing — true on any CI environment that
    // doesn't export MARKETDATA_TOKEN. This assertion is conditional
    // so the test stays valid in both cases.
    try (var client = MarketDataClient.builder().build()) {
      String envToken = System.getenv("MARKETDATA_TOKEN");
      boolean expectDemo = envToken == null || envToken.isBlank();
      assertThat(client.isDemoMode()).isEqualTo(expectDemo);
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
    try (var client = MarketDataClient.builder().apiKey("KEY").build()) {
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");
    }
  }

  @Test
  void rateLimitsStartUnpopulated() {
    try (var client = MarketDataClient.builder().apiKey("KEY").build()) {
      assertThat(client.getRateLimits()).isNull();
    }
  }
}
