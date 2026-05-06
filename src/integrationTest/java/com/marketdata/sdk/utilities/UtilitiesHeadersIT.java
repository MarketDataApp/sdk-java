package com.marketdata.sdk.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import com.marketdata.sdk.MarketDataClient;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Hits the live {@code GET /headers/} endpoint (root-level, no {@code /v1/} prefix). The endpoint
 * echoes back the headers the API received from us, so this is the strongest end-to-end check that
 * the SDK is actually sending the right Authorization, User-Agent, etc.
 *
 * <p>Gated by the {@code integrationTest} source set ({@code
 * MARKETDATA_RUN_INTEGRATION_TESTS=true}); requires a valid {@code MARKETDATA_TOKEN}.
 *
 * <p>Each scenario runs once for sync and once for async per SDK requirements §13.
 */
class UtilitiesHeadersIT {

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void headersEchoesUserAgentAndAuthorization(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      RequestHeaders headers = mode.headers(client.utilities());

      assertThat(headers.isEmpty()).isFalse();

      // The SDK's User-Agent must be sent on every request (SDK requirements §1.1):
      assertThat(headers.get("user-agent"))
          .as("the API echoes back the User-Agent we sent")
          .get()
          .asString()
          .startsWith("marketdata-sdk-java/");

      // Authorization is forwarded (and partially redacted in the response per the API docs):
      assertThat(headers.get("authorization"))
          .as("Authorization header was sent and echoed (redacted)")
          .get()
          .asString()
          .startsWith("Bearer ");

      // Accept comes from buildRequest in HttpTransport:
      assertThat(headers.get("accept")).get().asString().contains("application/json");
    }
  }

  @ParameterizedTest
  @EnumSource(CallMode.class)
  void headerLookupsAreCaseInsensitive(CallMode mode) {
    try (var client = MarketDataClient.builder().validateOnStartup(false).build()) {
      RequestHeaders headers = mode.headers(client.utilities());

      // The API normalizes keys to lowercase but our deserializer also forces lowercase, so
      // any of these capitalizations should resolve to the same value.
      assertThat(headers.get("User-Agent")).isEqualTo(headers.get("user-agent"));
      assertThat(headers.get("USER-AGENT")).isEqualTo(headers.get("user-agent"));
    }
  }

  enum CallMode {
    SYNC {
      @Override
      RequestHeaders headers(UtilitiesResource r) {
        return r.headers();
      }
    },
    ASYNC {
      @Override
      RequestHeaders headers(UtilitiesResource r) {
        try {
          return r.headersAsync().join();
        } catch (CompletionException e) {
          if (e.getCause() instanceof RuntimeException re) {
            throw re;
          }
          throw e;
        }
      }
    };

    abstract RequestHeaders headers(UtilitiesResource r);
  }
}
