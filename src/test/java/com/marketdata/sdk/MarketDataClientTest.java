package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.MarketDataException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class MarketDataClientTest {

  private static final Function<String, @Nullable String> NO_ENV = key -> null;

  private static Function<String, @Nullable String> envOf(Map<String, String> values) {
    return values::get;
  }

  private static Path noDotEnv(Path tmp) {
    return tmp.resolve("missing.env");
  }

  /** Reserves a fresh port and immediately releases it so connects target a known-closed socket. */
  private static String reserveClosedLocalUrl() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    return "http://127.0.0.1:" + port;
  }

  @Test
  void no_arg_constructor_resolves_defaults_and_returns_empty_rate_limits(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp))) {
      assertThat(client.getRateLimits()).isEqualTo(RateLimitSnapshot.EMPTY);
    }
  }

  @Test
  void four_arg_constructor_uses_explicit_values(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(
            "explicit-key", "https://explicit.example", "v9", false, NO_ENV, noDotEnv(tmp))) {
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
        new MarketDataClient("supersecret-token-YKT0", null, null, false, NO_ENV, noDotEnv(tmp))) {
      String repr = client.toString();

      assertThat(repr).doesNotContain("supersecret-token-YKT0");
      assertThat(repr).contains("***…***YKT0");
    }
  }

  @Test
  void to_string_shows_demo_mode_when_no_api_key(@TempDir Path tmp) {
    try (MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp))) {
      assertThat(client.toString()).contains("demoMode=true");
    }
  }

  // ---------- validateOnStartup wiring (end-to-end, no Runnable seam) ----------

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void validate_on_startup_true_attempts_validation(@TempDir Path tmp) throws Exception {
    // With a non-demo token and an unreachable baseUrl, the ctor must attempt the /user/ call
    // and surface the failure to the caller. If the validation hook ever gets disconnected from
    // the ctor flow, this test fails because construction would succeed silently.
    String unreachable = reserveClosedLocalUrl();

    assertThatThrownBy(
            () -> new MarketDataClient("any-token", unreachable, null, true, NO_ENV, noDotEnv(tmp)))
        .isInstanceOf(MarketDataException.class);
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void validate_on_startup_false_skips_validation_even_with_token(@TempDir Path tmp)
      throws Exception {
    // Symmetric case: a non-demo token + unreachable baseUrl + validateOnStartup=false must
    // construct cleanly. Any latent path that fires validation despite the flag would surface
    // here as a thrown ctor.
    String unreachable = reserveClosedLocalUrl();

    try (MarketDataClient client =
        new MarketDataClient("any-token", unreachable, null, false, NO_ENV, noDotEnv(tmp))) {
      assertThat(client.toString()).contains("demoMode=false");
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
            noDotEnv(tmp))) {
      assertThat(client.toString()).contains("***…***ABCD").contains("demoMode=false");
    }
  }

  @Test
  void close_is_idempotent(@TempDir Path tmp) {
    MarketDataClient client = new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp));

    client.close();
    client.close();
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void run_startup_validation_fails_fast_when_api_unreachable(@TempDir Path tmp) throws Exception {
    // §5 + retry policy: startup validation must use a single-attempt policy so a slow/down API
    // doesn't burn the full retry budget (~6.75 min worst case with defaults) before the
    // constructor returns. Drive a real connection-refused (closed local port) and assert the
    // failure surfaces well below even one default-policy retry would.
    String unreachable = reserveClosedLocalUrl();

    try (MarketDataClient client =
        new MarketDataClient("any-token", unreachable, null, false, NO_ENV, noDotEnv(tmp))) {
      long start = System.nanoTime();
      assertThatThrownBy(client::runStartupValidation).isInstanceOf(MarketDataException.class);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      // With the default retry policy this would have taken ~7 s minimum (1 s + 2 s + 4 s
      // backoffs between four attempts). A single-attempt run is bounded by connect-refused
      // latency, well under 2 s on any reasonable runner.
      assertThat(elapsedMs)
          .as("startup validation should not burn the retry budget")
          .isLessThan(2000);
    }
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void run_startup_validation_skips_in_demo_mode(@TempDir Path tmp) {
    // §5: when apiKey is unresolvable (demo mode), runStartupValidation must not hit /v1/user/ —
    // the server would return 401, breaking construction for any consumer who tries to "kick
    // the tires" without a token. The @Timeout guards against regression: if the skip ever
    // breaks, the test fails in 5s instead of hanging on the full retry budget (~6.75 min).
    try (MarketDataClient client =
        new MarketDataClient(null, null, null, false, NO_ENV, noDotEnv(tmp))) {
      assertThat(client.toString()).contains("demoMode=true");
      client.runStartupValidation(); // must return immediately, not make a network call
    }
  }

  @Test
  void quick_start_usage_resolves_real_environment_and_never_leaks_token() {
    // The no-arg public ctor now hits /v1/user/ for startup validation (§5). Don't exercise
    // that path here — this test asserts config resolution and token redaction, not the live
    // call. Use the 4-arg variant with validateOnStartup=false to keep this a pure unit test.
    try (MarketDataClient client = new MarketDataClient(null, null, null, false)) {
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
