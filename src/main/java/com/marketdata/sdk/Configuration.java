package com.marketdata.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves SDK configuration values per the cascade in SDK requirements §4: {@code explicit value →
 * MARKETDATA_* env var → .env file in CWD → built-in default}.
 *
 * <p>The single canonical construction path is {@link #loadFromProcess()}, which snapshots the live
 * environment and the {@code .env} file once. The constructor is strictly private — there is no
 * production-callable backdoor for injecting arbitrary maps. Tests reach the private constructor
 * via reflection (see {@code ConfigurationTest}); this is by design so a developer can't
 * accidentally take a shortcut around the canonical load path.
 */
final class Configuration {

  public static final String DEFAULT_BASE_URL = "https://api.marketdata.app";
  public static final String DEFAULT_API_VERSION = "v1";
  private static final Path DEFAULT_DOTENV_PATH = Paths.get(".env");

  private final Map<String, String> systemEnv;
  private final Map<String, String> dotEnv;

  private Configuration(Map<String, String> systemEnv, Map<String, String> dotEnv) {
    this.systemEnv = Map.copyOf(systemEnv);
    this.dotEnv = Map.copyOf(dotEnv);
  }

  /**
   * Production factory: snapshots {@code System.getenv()} and reads {@code ./.env} once. Call
   * during client construction.
   */
  public static Configuration loadFromProcess() {
    return new Configuration(System.getenv(), readDotEnvFile(DEFAULT_DOTENV_PATH));
  }

  /** Cascade: explicit → system env → .env → {@code null}. */
  public @Nullable String resolve(@Nullable String explicit, String envKey) {
    if (isPresent(explicit)) {
      return explicit;
    }
    String fromSystem = systemEnv.get(envKey);
    if (isPresent(fromSystem)) {
      return fromSystem;
    }
    String fromDotEnv = dotEnv.get(envKey);
    return isPresent(fromDotEnv) ? fromDotEnv : null;
  }

  /** Same as {@link #resolve} but returns {@code defaultValue} when the cascade yields nothing. */
  public String resolveOrDefault(@Nullable String explicit, String envKey, String defaultValue) {
    String resolved = resolve(explicit, envKey);
    return resolved != null ? resolved : defaultValue;
  }

  private static boolean isPresent(@Nullable String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Reads a {@code .env}-style file: lines like {@code KEY=value}, {@code #} for comments,
   * surrounding single or double quotes stripped. Package-private so tests can target an arbitrary
   * {@link Path} (e.g. inside a JUnit {@code @TempDir}) instead of CWD.
   */
  static Map<String, String> readDotEnvFile(Path path) {
    if (!Files.isRegularFile(path)) {
      return Map.of();
    }
    Map<String, String> result = new HashMap<>();
    try {
      for (String raw : Files.readAllLines(path)) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int eq = line.indexOf('=');
        if (eq < 1) {
          continue;
        }
        String key = line.substring(0, eq).trim();
        String value = stripQuotes(line.substring(eq + 1).trim());
        result.put(key, value);
      }
    } catch (IOException ignored) {
      return Map.of();
    }
    return Map.copyOf(result);
  }

  private static String stripQuotes(String value) {
    if (value.length() < 2) {
      return value;
    }
    char first = value.charAt(0);
    char last = value.charAt(value.length() - 1);
    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
