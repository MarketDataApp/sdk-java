package com.marketdata.sdk.markets;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.MarketDataClient;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Integration test against the live Market Data API. Gated by the {@code integrationTest} source
 * set, which itself only runs when {@code MARKETDATA_RUN_INTEGRATION_TESTS=true} is exported (see
 * {@code build.gradle.kts}).
 *
 * <p>Requires a valid {@code MARKETDATA_TOKEN} env var (or {@code .env} entry). Without one the
 * client enters demo mode and the {@code /markets/status/} endpoint is not on the demo allow-list,
 * so the test would receive an {@code AuthenticationException}.
 *
 * <p>Each scenario runs once for {@link CallMode#SYNC} and once for {@link CallMode#ASYNC} so we
 * satisfy SDK requirements §13's "tests must cover both sync and async variants for every endpoint"
 * against the real wire.
 */
class MarketsStatusIT {

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void todayStatusReturnsAtLeastOneEntry(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      MarketStatus status = mode.statusNoArgs(client.markets());

      // The endpoint always returns at least one entry for "today" — even on weekends/holidays
      // there's a row with status="closed".
      assertThat(status.days()).isNotEmpty();
      assertThat(status.days().get(0).date()).isNotNull();
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void historicalRangeReturnsExpectedDays(CallMode mode) {
    LocalDate from = LocalDate.now().minusDays(7);
    LocalDate to = LocalDate.now().minusDays(1);

    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      MarketStatus status = mode.statusForRange(client.markets(), from, to);

      assertThat(status.days()).hasSizeBetween(1, 7);
      assertThat(status.days())
          .allSatisfy(d -> assertThat(d.date()).isBetween(from.minusDays(1), to.plusDays(1)));
    }
  }
}
