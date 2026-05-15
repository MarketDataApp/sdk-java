package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketDataClientTest {

  private static final Function<String, @Nullable String> NO_ENV = key -> null;
  private static final Runnable NO_VALIDATION = () -> {};

  private static Function<String, @Nullable String> envOf(Map<String, String> values) {
    return values::get;
  }

  private static Path noDotEnv(Path tmp) {
    return tmp.resolve("missing.env");
  }

  @Test
  void no_arg_constructor_resolves_defaults_and_returns_empty_rate_limits(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp), NO_VALIDATION)) {
      assertThat(client.getRateLimits()).isEqualTo(RateLimitSnapshot.EMPTY);
    }
  }

  @Test
  void four_arg_constructor_uses_explicit_values(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(
            "explicit-key",
            "https://explicit.example",
            "v9",
            false,
            NO_ENV,
            noDotEnv(tmp),
            NO_VALIDATION)) {
      assertThat(client.toString())
          .contains("baseUrl=https://explicit.example")
          .contains("apiVersion=v9")
          .contains("demoMode=false")
          .doesNotContain("explicit-key");
    }
  }

  @Test
  void to_string_redacts_token(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(
            "supersecret-token-YKT0", null, null, false, NO_ENV, noDotEnv(tmp), NO_VALIDATION)) {
      String repr = client.toString();

      assertThat(repr).doesNotContain("supersecret-token-YKT0");
      assertThat(repr).contains("***…***YKT0");
    }
  }

  @Test
  void to_string_shows_demo_mode_when_no_api_key(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp), NO_VALIDATION)) {
      assertThat(client.toString()).contains("demoMode=true");
    }
  }

  @Test
  void validate_on_startup_true_invokes_validator(@TempDir Path tmp) {
    AtomicInteger calls = new AtomicInteger();

    try (MarketDataClient client =
        new MarketDataClient(
            "key", null, null, true, NO_ENV, noDotEnv(tmp), calls::incrementAndGet)) {
      assertThat(calls.get()).isEqualTo(1);
      assertThat(client.getRateLimits()).isEqualTo(RateLimitSnapshot.EMPTY);
    }
  }

  @Test
  void validate_on_startup_false_does_not_invoke_validator(@TempDir Path tmp) {
    AtomicInteger calls = new AtomicInteger();

    try (MarketDataClient client =
        new MarketDataClient(
            "key", null, null, false, NO_ENV, noDotEnv(tmp), calls::incrementAndGet)) {
      assertThat(calls.get()).isZero();
      assertThat(client.getRateLimits()).isEqualTo(RateLimitSnapshot.EMPTY);
    }
  }

  @Test
  void resolves_token_from_env_when_not_provided_explicitly(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(
            null,
            null,
            null,
            false,
            envOf(Map.of(EnvVars.TOKEN, "env-token-ABCD")),
            noDotEnv(tmp),
            NO_VALIDATION)) {
      assertThat(client.toString()).contains("***…***ABCD").contains("demoMode=false");
    }
  }

  @Test
  void close_is_idempotent(@TempDir Path tmp) {
    MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp), NO_VALIDATION);

    client.close();
    client.close();
  }

  @Test
  void quick_start_usage_resolves_real_environment_and_never_leaks_token() {
    try (MarketDataClient client = new MarketDataClient()) {
      assertThat(client.getRateLimits()).isEqualTo(RateLimitSnapshot.EMPTY);
      assertThat(client.toString()).startsWith("MarketDataClient[").endsWith("]");

      String envToken = System.getenv(EnvVars.TOKEN);
      if (envToken != null && !envToken.isBlank()) {
        assertThat(client.toString()).doesNotContain(envToken);
      }

      String envBaseUrl = System.getenv(EnvVars.BASE_URL);
      if (envBaseUrl == null || envBaseUrl.isBlank()) {
        assertThat(client.toString()).contains("baseUrl=" + Configuration.DEFAULT_BASE_URL);
      }
    }
  }
}
