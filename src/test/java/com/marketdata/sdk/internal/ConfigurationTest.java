package com.marketdata.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {

  /**
   * Reflection bridge to {@code Configuration}'s private constructor. Tests need to inject custom
   * environment maps; production code cannot — that's the entire point of keeping the constructor
   * private. Encapsulating the reflection here keeps individual tests clean.
   */
  private static Configuration newConfig(
      Map<String, String> systemEnv, Map<String, String> dotEnv) {
    try {
      Constructor<Configuration> ctor =
          Configuration.class.getDeclaredConstructor(Map.class, Map.class);
      ctor.setAccessible(true);
      return ctor.newInstance(systemEnv, dotEnv);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Could not construct Configuration via reflection — has the private ctor signature"
              + " changed?",
          e);
    }
  }

  @Test
  void explicitWinsOverEverything() {
    Configuration config =
        newConfig(
            Map.of("MARKETDATA_TOKEN", "from-env"), Map.of("MARKETDATA_TOKEN", "from-dotenv"));

    assertThat(config.resolve("explicit-value", "MARKETDATA_TOKEN")).isEqualTo("explicit-value");
  }

  @Test
  void envVarWinsOverDotEnv() {
    Configuration config =
        newConfig(
            Map.of("MARKETDATA_TOKEN", "from-env"), Map.of("MARKETDATA_TOKEN", "from-dotenv"));

    assertThat(config.resolve(null, "MARKETDATA_TOKEN")).isEqualTo("from-env");
  }

  @Test
  void fallsBackToDotEnvWhenEnvVarMissing() {
    Configuration config = newConfig(Map.of(), Map.of("MARKETDATA_TOKEN", "from-dotenv"));

    assertThat(config.resolve(null, "MARKETDATA_TOKEN")).isEqualTo("from-dotenv");
  }

  @Test
  void blankExplicitDoesNotCount() {
    Configuration config = newConfig(Map.of("MARKETDATA_TOKEN", "from-env"), Map.of());

    assertThat(config.resolve("   ", "MARKETDATA_TOKEN")).isEqualTo("from-env");
  }

  @Test
  void blankEnvVarFallsThroughToDotEnv() {
    Configuration config =
        newConfig(Map.of("MARKETDATA_TOKEN", "  "), Map.of("MARKETDATA_TOKEN", "from-dotenv"));

    assertThat(config.resolve(null, "MARKETDATA_TOKEN")).isEqualTo("from-dotenv");
  }

  @Test
  void resolveReturnsNullWhenAllSourcesEmpty() {
    Configuration config = newConfig(Map.of(), Map.of());

    assertThat(config.resolve(null, "MARKETDATA_TOKEN")).isNull();
  }

  @Test
  void resolveOrDefaultReturnsDefaultWhenAllEmpty() {
    Configuration config = newConfig(Map.of(), Map.of());

    assertThat(config.resolveOrDefault(null, "MARKETDATA_BASE_URL", "https://default"))
        .isEqualTo("https://default");
  }

  @Test
  void resolveOrDefaultPrefersResolvedValue() {
    Configuration config = newConfig(Map.of("MARKETDATA_BASE_URL", "https://explicit"), Map.of());

    assertThat(config.resolveOrDefault(null, "MARKETDATA_BASE_URL", "https://default"))
        .isEqualTo("https://explicit");
  }

  // ---------- .env file parsing ----------

  @Test
  void readsAndParsesDotEnvFile(@TempDir Path tmp) throws IOException {
    Path dotenv = tmp.resolve(".env");
    Files.writeString(
        dotenv,
        """
        # comment line — should be ignored
        MARKETDATA_TOKEN=plain-token
        MARKETDATA_BASE_URL="https://staging.example.com"
        QUOTED_SINGLE='single-quoted'
        EMPTY_VALUE=

        # blank line above
        BAD_LINE_NO_EQUALS
        =BAD_LINE_NO_KEY
        """);

    Map<String, String> parsed = Configuration.readDotEnvFile(dotenv);

    assertThat(parsed)
        .containsEntry("MARKETDATA_TOKEN", "plain-token")
        .containsEntry("MARKETDATA_BASE_URL", "https://staging.example.com")
        .containsEntry("QUOTED_SINGLE", "single-quoted")
        .containsEntry("EMPTY_VALUE", "")
        .doesNotContainKey("# comment line — should be ignored")
        .doesNotContainKey("BAD_LINE_NO_EQUALS");
  }

  @Test
  void missingDotEnvReturnsEmpty(@TempDir Path tmp) {
    assertThat(Configuration.readDotEnvFile(tmp.resolve(".env"))).isEmpty();
  }

  @Test
  void dotEnvParsingIntegratesWithCascade(@TempDir Path tmp) throws IOException {
    Path dotenv = tmp.resolve(".env");
    Files.writeString(dotenv, "MARKETDATA_TOKEN=from-real-dotenv\n");

    Configuration config = newConfig(Map.of(), Configuration.readDotEnvFile(dotenv));

    assertThat(config.resolve(null, "MARKETDATA_TOKEN")).isEqualTo("from-real-dotenv");
  }
}
