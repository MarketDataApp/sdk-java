package com.marketdata.sdk.markets;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.MarketDataClient;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Integration test against the live Market Data API. Gated by the {@code integrationTest} source
 * set, which itself only runs when {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} is exported (see
 * {@code build.gradle.kts}).
 *
 * <p>Requires a valid {@code MARKETDATA_TOKEN} env var (or {@code .env} entry); without it the
 * client enters demo mode and the markets endpoint is not part of the demo allow-list, so the test
 * would receive an authentication error.
 */
class MarketsStatusIT {

  @Test
  void todayStatusReturnsAtLeastOneEntry() {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      MarketStatus status = client.markets().status();

      // The endpoint always returns at least one entry for "today" — even on weekends/holidays
      // the row is present with status="closed".
      assertThat(status.days()).isNotEmpty();
      assertThat(status.days().get(0).date()).isNotNull();
    }
  }

  @Test
  void historicalRangeReturnsExpectedDays() {
    LocalDate from = LocalDate.now().minusDays(7);
    LocalDate to = LocalDate.now().minusDays(1);

    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      MarketStatus status = client.markets().status(from, to);

      assertThat(status.days()).hasSizeBetween(1, 7);
      assertThat(status.days())
          .allSatisfy(
              d -> {
                assertThat(d.date()).isBetween(from.minusDays(1), to.plusDays(1));
              });
    }
  }
}
