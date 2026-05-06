package com.marketdata.sdk.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.MarketDataClient;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Hits the live {@code GET /status/} endpoint (root-level, no {@code /v1/} prefix). Gated by the
 * {@code integrationTest} source set ({@code MARKETDATA_RUN_INTEGRATION_TESTS=true}).
 *
 * <p>This endpoint does <em>not</em> require authentication, but the SDK will still send the {@code
 * Authorization} header if a token is configured — the API simply ignores it for status lookups. We
 * don't validate that here because there's no observable difference.
 *
 * <p>Each scenario runs once for sync and once for async per SDK requirements §13.
 */
class UtilitiesStatusIT {

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void statusReturnsServicesWithStructuralInvariants(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      ServiceStatus status = mode.status(client.utilities());

      assertThat(status.services()).isNotEmpty();
      assertThat(status.services())
          .allSatisfy(
              s -> {
                assertThat(s.service()).isNotBlank();
                assertThat(s.status()).isIn("online", "offline");
                // online flag is consistent with the human-readable status:
                assertThat(s.online()).isEqualTo("online".equals(s.status()));
                assertThat(s.uptimePct30d()).isBetween(0.0, 1.0);
                assertThat(s.uptimePct90d()).isBetween(0.0, 1.0);
                assertThat(s.updated()).isNotNull();
              });
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void statusUrlIsRootLevelNotV1(CallMode mode) {
    // No direct way to introspect the URL from a successful call, but the
    // fact that the call doesn't 404 (which it would if we mistakenly hit
    // /v1/status/) is the implicit assertion. If this passes, the
    // RequestSpec.getAtRoot wiring is correctly skipping the version
    // prefix.
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      ServiceStatus result = mode.status(client.utilities());
      assertThat(result).isNotNull();
    }
  }

  enum CallMode {
    SYNC {
      @Override
      ServiceStatus status(UtilitiesResource r) {
        return r.status();
      }
    },
    ASYNC {
      @Override
      ServiceStatus status(UtilitiesResource r) {
        try {
          return r.statusAsync().join();
        } catch (CompletionException e) {
          if (e.getCause() instanceof RuntimeException re) {
            throw re;
          }
          throw e;
        }
      }
    };

    abstract ServiceStatus status(UtilitiesResource r);
  }
}
