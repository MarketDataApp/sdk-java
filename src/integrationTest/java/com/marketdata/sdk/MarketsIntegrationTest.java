package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for the {@code markets} resource against the live Market Data API. Gated by the
 * {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} environment variable in {@code build.gradle.kts}; a
 * valid {@code MARKETDATA_TOKEN} is also required.
 *
 * <p>Tests assert <strong>shape</strong> rather than specific values, since live data drifts daily.
 * Status is asserted as {@code 200 || 203} (203 = cached/delayed data, which the SDK surfaces as
 * success).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketsIntegrationTest {

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
  void statusReturnsOneRowPerDayInRange() {
    MarketStatusResponse resp =
        client
            .markets()
            .status(
                MarketStatusRequest.builder()
                    .from(LocalDate.now().minusDays(7))
                    .to(LocalDate.now())
                    .build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).as("an 8-day window always contains rows").isNotEmpty();
    // Any 8-day window contains a weekend, so both statuses must show up.
    assertThat(resp.values().stream().anyMatch(MarketStatus::isOpen)).isTrue();
    assertThat(resp.values().stream().anyMatch(MarketStatus::isClosed)).isTrue();
    MarketStatus first = resp.values().get(0);
    assertThat(first.date()).isNotNull();
    assertThat(first.date().getZone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void statusCountbackWindowDecodes() {
    MarketStatusResponse resp =
        client
            .markets()
            .status(MarketStatusRequest.builder().to(LocalDate.now()).countback(5).build());

    assertThat(resp.statusCode()).isIn(200, 203);
    assertThat(resp.values()).isNotEmpty();
    for (MarketStatus day : resp.values()) {
      // Every row inside the calendar's coverage carries open/closed; none should be blank.
      if (day.status() != null) {
        assertThat(day.isOpen() || day.isClosed()).isTrue();
      }
    }
  }
}
