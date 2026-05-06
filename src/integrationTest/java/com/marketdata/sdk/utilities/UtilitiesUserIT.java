package com.marketdata.sdk.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.MarketDataClient;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Hits the live {@code GET /user/} endpoint and validates the round-trip. Gated by the {@code
 * integrationTest} source set ({@code MARKETDATA_RUN_INTEGRATION_TESTS=true}); requires a valid
 * {@code MARKETDATA_TOKEN}.
 *
 * <p>Each scenario runs once for sync and once for async per SDK requirements §13.
 */
class UtilitiesUserIT {

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void userReturnsAccountSnapshot(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      UserInfo info = mode.user(client.utilities());

      // We don't know exact quota numbers (depends on the test account / time of day),
      // but the structural invariants hold for any well-formed account.
      assertThat(info.requestsLimit()).isPositive();
      assertThat(info.requestsRemaining()).isGreaterThanOrEqualTo(0);
      assertThat(info.requestsRemaining()).isLessThanOrEqualTo(info.requestsLimit());
      assertThat(info.optionsDataPermissions()).isNotNull();
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void afterUserCallTheClientHasAFreshRateLimitSnapshot(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      assertThat(client.getRateLimits()).isNull();

      mode.user(client.utilities());

      // §8.1: x-api-ratelimit-* headers populate the snapshot on the way back.
      assertThat(client.getRateLimits()).isNotNull();
      assertThat(client.getRateLimits().limit()).isPositive();
    }
  }

  /** Mirrors the test-side {@code CallMode} from the unit suite. */
  enum CallMode {
    SYNC {
      @Override
      UserInfo user(UtilitiesResource r) {
        return r.user();
      }
    },
    ASYNC {
      @Override
      UserInfo user(UtilitiesResource r) {
        try {
          return r.userAsync().join();
        } catch (CompletionException e) {
          if (e.getCause() instanceof RuntimeException re) {
            throw re;
          }
          throw e;
        }
      }
    };

    abstract UserInfo user(UtilitiesResource r);
  }
}
