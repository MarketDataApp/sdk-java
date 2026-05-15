package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

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
}
