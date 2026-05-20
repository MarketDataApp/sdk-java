package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {

  private static final Function<String, @Nullable String> NO_ENV = key -> null;

  private static Function<String, @Nullable String> envOf(Map<String, String> values) {
    return values::get;
  }

  private static Path noDotEnv(@TempDir Path tmp) {
    return tmp.resolve("missing.env");
  }

  @Test
  void resolve_uses_explicit_values_when_provided(@TempDir Path tmp) {
    Configuration config =
        Configuration.resolve(
            "explicit-key",
            "https://explicit.example",
            "v9",
            envOf(
                Map.of(
                    EnvVars.TOKEN, "env-key",
                    EnvVars.BASE_URL, "https://env.example",
                    EnvVars.API_VERSION, "v0")),
            noDotEnv(tmp));

    assertThat(config.apiKey()).isEqualTo("explicit-key");
    assertThat(config.baseUrl()).isEqualTo("https://explicit.example");
    assertThat(config.apiVersion()).isEqualTo("v9");
  }

  @Test
  void resolve_falls_back_to_env_when_explicit_missing(@TempDir Path tmp) {
    Configuration config =
        Configuration.resolve(
            null,
            null,
            null,
            envOf(
                Map.of(
                    EnvVars.TOKEN, "env-key",
                    EnvVars.BASE_URL, "https://env.example",
                    EnvVars.API_VERSION, "v2")),
            noDotEnv(tmp));

    assertThat(config.apiKey()).isEqualTo("env-key");
    assertThat(config.baseUrl()).isEqualTo("https://env.example");
    assertThat(config.apiVersion()).isEqualTo("v2");
  }

  @Test
  void resolve_falls_back_to_dotenv_when_explicit_and_env_missing(@TempDir Path tmp)
      throws IOException {
    Path dotEnv =
        Files.writeString(
            tmp.resolve(".env"),
            """
                MARKETDATA_TOKEN=dotenv-key
                MARKETDATA_BASE_URL=https://dotenv.example
                MARKETDATA_API_VERSION=v3
                """);

    Configuration config = Configuration.resolve(null, null, null, NO_ENV, dotEnv);

    assertThat(config.apiKey()).isEqualTo("dotenv-key");
    assertThat(config.baseUrl()).isEqualTo("https://dotenv.example");
    assertThat(config.apiVersion()).isEqualTo("v3");
  }

  @Test
  void resolve_ignores_non_marketdata_keys_in_dotenv(@TempDir Path tmp) throws IOException {
    // The cascade hands DotEnvLoader the EnvVars allowlist; secrets unrelated to the SDK (AWS
    // creds, GitHub tokens, etc.) must not leak into the SDK's memory just because they share a
    // .env file with the MARKETDATA_* keys. Spec §16's allowlist principle (defined for
    // System.getenv via EnvVars.systemLookup) extends to .env reads.
    Path dotEnv =
        Files.writeString(
            tmp.resolve(".env"),
            """
                MARKETDATA_TOKEN=marketdata-key
                AWS_SECRET_ACCESS_KEY=sk-aws-supersecret
                GITHUB_TOKEN=ghp-leaked
                """);

    Configuration config = Configuration.resolve(null, null, null, NO_ENV, dotEnv);

    assertThat(config.apiKey()).isEqualTo("marketdata-key");
    // No accessor exposes non-MARKETDATA values — but verify the cascade did not pluck them
    // through some accidental future path by snapshotting the record's toString.
    assertThat(config.toString()).doesNotContain("supersecret").doesNotContain("leaked");
  }

  @Test
  void resolve_uses_defaults_for_base_url_and_api_version_when_nothing_provided(@TempDir Path tmp) {
    Configuration config = Configuration.resolve(null, null, null, NO_ENV, noDotEnv(tmp));

    assertThat(config.baseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
    assertThat(config.apiVersion()).isEqualTo(Configuration.DEFAULT_API_VERSION);
  }

  @Test
  void resolve_leaves_api_key_null_when_nothing_provided(@TempDir Path tmp) {
    Configuration config = Configuration.resolve(null, null, null, NO_ENV, noDotEnv(tmp));

    assertThat(config.apiKey()).isNull();
  }

  @Test
  void resolve_treats_blank_explicit_as_missing(@TempDir Path tmp) {
    Configuration config =
        Configuration.resolve(
            "   ",
            "",
            "\t",
            envOf(
                Map.of(
                    EnvVars.TOKEN, "env-key",
                    EnvVars.BASE_URL, "https://env.example",
                    EnvVars.API_VERSION, "v2")),
            noDotEnv(tmp));

    assertThat(config.apiKey()).isEqualTo("env-key");
    assertThat(config.baseUrl()).isEqualTo("https://env.example");
    assertThat(config.apiVersion()).isEqualTo("v2");
  }

  @Test
  void resolve_explicit_beats_env_beats_dotenv_beats_default(@TempDir Path tmp) throws IOException {
    Path dotEnv =
        Files.writeString(
            tmp.resolve(".env"),
            """
                MARKETDATA_TOKEN=dotenv-key
                MARKETDATA_BASE_URL=https://dotenv.example
                """);

    Configuration withExplicit =
        Configuration.resolve(
            "explicit-key",
            "https://explicit.example",
            null,
            envOf(
                Map.of(
                    EnvVars.TOKEN, "env-key",
                    EnvVars.BASE_URL, "https://env.example")),
            dotEnv);

    assertThat(withExplicit.apiKey()).isEqualTo("explicit-key");
    assertThat(withExplicit.baseUrl()).isEqualTo("https://explicit.example");

    Configuration withoutExplicit =
        Configuration.resolve(
            null,
            null,
            null,
            envOf(
                Map.of(
                    EnvVars.TOKEN, "env-key",
                    EnvVars.BASE_URL, "https://env.example")),
            dotEnv);

    assertThat(withoutExplicit.apiKey()).isEqualTo("env-key");
    assertThat(withoutExplicit.baseUrl()).isEqualTo("https://env.example");

    Configuration onlyDotEnv = Configuration.resolve(null, null, null, NO_ENV, dotEnv);

    assertThat(onlyDotEnv.apiKey()).isEqualTo("dotenv-key");
    assertThat(onlyDotEnv.baseUrl()).isEqualTo("https://dotenv.example");
  }

  @Test
  void resolve_picks_up_logging_level_and_date_format_from_env(@TempDir Path tmp) {
    Configuration config =
        Configuration.resolve(
            null,
            null,
            null,
            envOf(
                Map.of(
                    EnvVars.LOGGING_LEVEL, "DEBUG",
                    EnvVars.DATE_FORMAT, "unix")),
            noDotEnv(tmp));

    assertThat(config.loggingLevel()).isEqualTo("DEBUG");
    assertThat(config.dateFormat()).isEqualTo("unix");
  }

  @Test
  void resolve_leaves_logging_level_and_date_format_null_when_unset(@TempDir Path tmp) {
    Configuration config = Configuration.resolve(null, null, null, NO_ENV, noDotEnv(tmp));

    assertThat(config.loggingLevel()).isNull();
    assertThat(config.dateFormat()).isNull();
  }

  @Test
  void resolve_env_lookup_returning_blank_is_treated_as_missing(@TempDir Path tmp) {
    Configuration config =
        Configuration.resolve(
            null,
            null,
            null,
            envOf(
                Map.of(
                    EnvVars.TOKEN, "   ",
                    EnvVars.BASE_URL, "")),
            noDotEnv(tmp));

    assertThat(config.apiKey()).isNull();
    assertThat(config.baseUrl()).isEqualTo(Configuration.DEFAULT_BASE_URL);
  }

  // ---------- normalization ----------

  @Test
  void resolve_strips_trailing_slashes_from_baseUrl(@TempDir Path tmp) {
    // Single trailing slash from the user is the common copy-paste mistake; multiple slashes
    // (e.g. "https://x///") are pathological but cheap to handle and avoid surprises.
    Configuration single =
        Configuration.resolve(null, "https://api.example.com/", null, NO_ENV, noDotEnv(tmp));
    Configuration many =
        Configuration.resolve(null, "https://api.example.com///", null, NO_ENV, noDotEnv(tmp));
    Configuration whitespaced =
        Configuration.resolve(null, "  https://api.example.com/  ", null, NO_ENV, noDotEnv(tmp));

    assertThat(single.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(many.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(whitespaced.baseUrl()).isEqualTo("https://api.example.com");
  }

  @Test
  void resolve_strips_leading_and_trailing_slashes_from_apiVersion(@TempDir Path tmp) {
    // "v1", "/v1", "v1/", and "/v1/" should all collapse to the same canonical form so URI
    // composition is independent of the user's spelling.
    Configuration leading = Configuration.resolve(null, null, "/v1", NO_ENV, noDotEnv(tmp));
    Configuration trailing = Configuration.resolve(null, null, "v1/", NO_ENV, noDotEnv(tmp));
    Configuration both = Configuration.resolve(null, null, "/v1/", NO_ENV, noDotEnv(tmp));
    Configuration whitespaced =
        Configuration.resolve(null, null, "  /v1/  ", NO_ENV, noDotEnv(tmp));

    assertThat(leading.apiVersion()).isEqualTo("v1");
    assertThat(trailing.apiVersion()).isEqualTo("v1");
    assertThat(both.apiVersion()).isEqualTo("v1");
    assertThat(whitespaced.apiVersion()).isEqualTo("v1");
  }

  @Test
  void resolve_default_baseUrl_already_has_no_trailing_slash(@TempDir Path tmp) {
    Configuration config = Configuration.resolve(null, null, null, NO_ENV, noDotEnv(tmp));

    assertThat(config.baseUrl()).doesNotEndWith("/");
  }

  // ---------- validation: baseUrl ----------

  @Test
  void resolve_rejects_baseUrl_without_scheme(@TempDir Path tmp) {
    // The classic "I forgot https://" mistake — URI.create accepts it as a relative path, but
    // HttpClient.send then surfaces a cryptic "URI is not absolute". Fail at construction.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> Configuration.resolve(null, "api.marketdata.app", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("scheme http or https");
  }

  @Test
  void resolve_rejects_baseUrl_with_disallowed_scheme(@TempDir Path tmp) {
    // file://, ftp://, javascript: — schemes the SDK has no business opening.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> Configuration.resolve(null, "file:///etc/passwd", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("scheme http or https");
  }

  @Test
  void resolve_accepts_http_and_https(@TempDir Path tmp) {
    Configuration https =
        Configuration.resolve(null, "https://api.example.com", null, NO_ENV, noDotEnv(tmp));
    Configuration http =
        Configuration.resolve(null, "http://localhost:9000", null, NO_ENV, noDotEnv(tmp));

    assertThat(https.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(http.baseUrl()).isEqualTo("http://localhost:9000");
  }

  @Test
  void resolve_accepts_baseUrl_with_path_prefix(@TempDir Path tmp) {
    // Self-hosted / reverse-proxy setups: the API lives under /marketdata-proxy on a corp host.
    Configuration config =
        Configuration.resolve(
            null, "https://corp.example.com/marketdata-proxy", null, NO_ENV, noDotEnv(tmp));

    assertThat(config.baseUrl()).isEqualTo("https://corp.example.com/marketdata-proxy");
  }

  @Test
  void resolve_rejects_baseUrl_missing_host(@TempDir Path tmp) {
    // Opaque URIs ({@code scheme:opaque}, no {@code //authority}) parse fine but expose a null
    // host — those are the inputs the "missing a host" guard exists to catch.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, "https:opaque", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("missing a host");
  }

  @Test
  void resolve_rejects_baseUrl_with_invalid_syntax(@TempDir Path tmp) {
    // "https://" normalizes to "https:" which fails URI.parse outright — the test verifies the
    // "is not a valid URI" branch fires with a clear message rather than letting the syntax
    // exception bubble up unwrapped.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, "https://", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("is not a valid URI");
  }

  @Test
  void resolve_rejects_baseUrl_with_query_string(@TempDir Path tmp) {
    // Query belongs on requests, not the origin. Letting it through would corrupt every URL the
    // transport composes (`?token=abc/v1/markets/status/`).
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Configuration.resolve(
                    null, "https://api.example.com?token=x", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("query string");
  }

  @Test
  void resolve_rejects_baseUrl_with_fragment(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Configuration.resolve(
                    null, "https://api.example.com#frag", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("fragment");
  }

  @Test
  void resolve_rejects_baseUrl_with_user_info(@TempDir Path tmp) {
    // user:pass@host has Basic-auth semantics the SDK does not support — and would leak
    // credentials into log lines that include the URL.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Configuration.resolve(
                    null, "https://user:pass@api.example.com", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("user-info");
  }

  @Test
  void resolve_rejects_baseUrl_that_normalizes_to_empty(@TempDir Path tmp) {
    // "////" passes pickFirstOrDefault (not blank), normalizes to "", then validation must
    // reject the empty result rather than silently falling through.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, "////", null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("must not be empty");
  }

  // ---------- validation: apiVersion ----------

  @Test
  void resolve_accepts_valid_apiVersion_shapes(@TempDir Path tmp) {
    // Permissive enough for the realistic variants — semver-ish, branch-tagged, etc.
    for (String version : new String[] {"v1", "v2", "v1.0", "v2.1.0", "beta-1", "alpha_2"}) {
      Configuration config = Configuration.resolve(null, null, version, NO_ENV, noDotEnv(tmp));
      assertThat(config.apiVersion()).isEqualTo(version);
    }
  }

  @Test
  void resolve_rejects_apiVersion_with_embedded_slash(@TempDir Path tmp) {
    // Mid-string slashes survive the leading/trailing strip, but they'd inject extra path
    // segments — "v1/extra" → /v1/extra/markets/status/ which the server treats as a different
    // resource.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, null, "v1/extra", NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("[A-Za-z0-9._-]+");
  }

  @Test
  void resolve_rejects_apiVersion_with_spaces(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, null, "v 1", NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("[A-Za-z0-9._-]+");
  }

  @Test
  void resolve_rejects_apiVersion_already_percent_encoded(@TempDir Path tmp) {
    // Double-encoding territory — "%2F" would become "%252F" on the wire and the server would
    // see the literal text, not a slash.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, null, "%2Fv1", NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("[A-Za-z0-9._-]+");
  }

  @Test
  void resolve_rejects_apiVersion_that_normalizes_to_empty(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, null, "////", NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("must not be empty");
  }

  @Test
  void resolve_validates_values_from_dotenv_too(@TempDir Path tmp) throws IOException {
    // The validator runs after the cascade picks a value — bad input from env vars or .env files
    // must surface the same IAE, not slip through because the cascade source was non-explicit.
    Path dotEnv = Files.writeString(tmp.resolve(".env"), "MARKETDATA_BASE_URL=not-a-url\n");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(null, null, null, NO_ENV, dotEnv))
        .withMessageContaining("scheme http or https");
  }

  // ---------- §16 / issue #23: apiKey character validation ----------

  /**
   * A token loaded from a .env file with a stray CRLF must be rejected at construction. Without
   * this gate, the failure surfaces only at the first request as a cryptic IAE from {@code
   * HttpRequest.Builder#header}, miles away from the actual source of the bad input.
   */
  @Test
  void resolve_rejects_apiKey_with_carriage_return(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> Configuration.resolve("good-prefix\rbad", null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("invalid character")
        .withMessageContaining("offset 11");
  }

  @Test
  void resolve_rejects_apiKey_with_newline(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve("token\nmore", null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("invalid character");
  }

  @Test
  void resolve_rejects_apiKey_with_tab(@TempDir Path tmp) {
    // Tab (0x09) is below 0x20 — also rejected. Real tokens never contain tabs; if one appears
    // it's a copy-paste artifact from a spreadsheet cell or formatted document.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve("token\tmore", null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("invalid character");
  }

  @Test
  void resolve_rejects_apiKey_with_high_bit_byte(@TempDir Path tmp) {
    // Non-ASCII (e.g. UTF-8 multi-byte) — almost always means the .env was decoded with the wrong
    // charset and the original token is unusable anyway. Failing fast with a clear message beats
    // a stream of authentication failures from the server.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve("tokén-ABCD", null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("invalid character");
  }

  @Test
  void resolve_rejects_apiKey_with_nul_byte(@TempDir Path tmp) {
    // A literal NUL (0x00) - far below the 0x20 floor; canonical "this token is corrupt". Built
    // at runtime so the test source file does not carry an embedded NUL byte itself.
    String tokenWithNul = "token" + (char) 0x00 + "more";
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Configuration.resolve(tokenWithNul, null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageContaining("invalid character");
  }

  @Test
  void resolve_accepts_apiKey_with_printable_ascii(@TempDir Path tmp) {
    // Regression guard: tokens that legitimately use the full printable ASCII range
    // (letters, digits, `.-_+/=` and friends) must not be rejected.
    Configuration cfg =
        Configuration.resolve("ABCdef-123_token.with+slashes/=", null, null, NO_ENV, noDotEnv(tmp));
    assertThat(cfg.apiKey()).isEqualTo("ABCdef-123_token.with+slashes/=");
  }

  @Test
  void resolve_does_not_validate_null_apiKey(@TempDir Path tmp) {
    // Demo mode: no token at all is a supported cascade outcome; validation must not flag it.
    Configuration cfg = Configuration.resolve(null, null, null, NO_ENV, noDotEnv(tmp));
    assertThat(cfg.apiKey()).isNull();
  }

  /**
   * The error message must NOT echo the token. The token's offset and the offending code point are
   * enough for diagnostics; the token itself never appears in {@code getMessage()} (§16).
   */
  @Test
  void apiKey_validation_error_does_not_leak_token(@TempDir Path tmp) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Configuration.resolve(
                    "supersecret-prefix\rsuffix-leak", null, null, NO_ENV, noDotEnv(tmp)))
        .withMessageNotContaining("supersecret-prefix")
        .withMessageNotContaining("suffix-leak");
  }
}
