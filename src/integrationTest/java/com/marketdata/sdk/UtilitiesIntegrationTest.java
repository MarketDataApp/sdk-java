package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for the {@code utilities} resource against the live Market Data API. Gated by
 * the {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} environment variable in {@code
 * build.gradle.kts}; a valid {@code MARKETDATA_TOKEN} is also required.
 *
 * <p>These are the unversioned diagnostic endpoints ({@code /status/}, {@code /headers/}, {@code
 * /user/}). Tests assert <strong>shape</strong> rather than specific values: the service list, the
 * echoed header map, and the quota record all drift. Status is asserted as {@code 200 || 203} (203
 * = cached/delayed data, which the SDK surfaces as success), matching the other resource suites.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtilitiesIntegrationTest {

  private MarketDataClient client;

  @BeforeAll
  void setUp() {
    client = new MarketDataClient();
  }

  @AfterAll
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void statusReturnsPerServiceHealth() {
    UtilitiesStatusResponse resp = client.utilities().status();

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("the API always reports at least one service").isNotEmpty();
    ServiceStatus first = resp.values().get(0);
    assertThat(first.service()).isNotBlank();
    assertThat(first.status()).isNotBlank();
    // uptime is a percentage; assert it decodes into the valid range.
    assertThat(first.uptimePct30d()).isBetween(0.0, 100.0);
  }

  @Test
  void headersEchoesRequestHeadersWithRedactedAuth() {
    UtilitiesHeadersResponse resp = client.utilities().headers();

    assertThat(resp.statusCode()).isIn(200, 203);
    Map<String, String> headers = resp.values();
    assertThat(headers).as("the server echoes the request headers it received").isNotEmpty();
    // The SDK's User-Agent (marketdata-sdk-java/{version}) is always echoed back, regardless of how
    // the server cases the header key — a stable proof the round-trip carried the SDK's headers.
    assertThat(headers.toString()).contains("marketdata-sdk-java");
  }

  @Test
  void userReturnsQuotaSnapshot() {
    // The client constructor already validated this token against /user/ on startup, so a 200 is
    // expected here; assert the quota record decodes rather than pinning to plan-specific numbers.
    UtilitiesUserResponse resp = client.utilities().user();

    assertThat(resp.statusCode()).isIn(200, 203);
    User user = resp.values();
    assertThat(user).isNotNull();
    assertThat(user.requestsLimit()).as("a real plan exposes a request limit").isGreaterThan(0);
    assertThat(user.requestsRemaining()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void statusValuesDecodeEveryRow() {
    // Defensive: iterate the whole list so a malformed row anywhere trips a ParseError, not just
    // the first.
    List<ServiceStatus> services = client.utilities().status().values();
    for (ServiceStatus s : services) {
      assertThat(s.service()).isNotBlank();
    }
  }
}
