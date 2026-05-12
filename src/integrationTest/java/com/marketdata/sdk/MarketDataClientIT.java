package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Integration smoke for {@link MarketDataClient} construction against the live Market Data API.
 * Gated by the {@code integrationTest} source set, which itself only runs when {@code
 * MARKETDATA_RUN_INTEGRATION_TESTS=true}. Requires a valid {@code MARKETDATA_TOKEN} via env or
 * {@code .env} — without one the no-arg ctor falls back to demo mode and skips {@code /user/}, so
 * the smoke would assert nothing real.
 *
 * <p>These assertions used to live in {@code MarketDataClientTest} as unit tests, but after §5
 * landed the no-arg ctor performs a real HTTP call ({@code GET /user/}) when {@code
 * validateOnStartup=true}. Unit tests must not depend on the network; the smoke moved here so the
 * real-API path stays exercised exactly once, under the integration gate.
 */
class MarketDataClientIT {

  @Test
  void noArgConstructorAppliesProductionDefaults() {
    try (var client = new MarketDataClient()) {
      assertThat(client.isValidateOnStartup())
          .as(
              "the no-arg ctor must be equivalent to `new MarketDataClient(null, null, null, true)`")
          .isTrue();
      assertThat(client.getUserAgent()).startsWith("marketdata-sdk-java/");

      // baseUrl/apiVersion fall back to the documented defaults only when the cascade has no
      // override. CI typically has no overrides; locally a developer may set MARKETDATA_BASE_URL
      // for a staging environment, so gate those assertions on the env vars being unset.
      if (System.getenv("MARKETDATA_BASE_URL") == null) {
        assertThat(client.getBaseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
      }
      if (System.getenv("MARKETDATA_API_VERSION") == null) {
        assertThat(client.getApiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
      }

      // After /user/ ran, the rate-limit snapshot must be seeded — that confirms the §5 wiring
      // travels end-to-end (request fired, response headers parsed, snapshot stored).
      assertThat(client.getRateLimits())
          .as("startup validation hit /user/ — snapshot should be seeded")
          .isNotNull();
    }
  }
}
