package com.marketdata.sdk.internal;

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
 * <p>The {@code .env} file is read lazily from the current working directory when an env-backed
 * value is requested. Lines starting with {@code #} are treated as comments; surrounding single or
 * double quotes on values are stripped.
 */
public final class Configuration {

  public static final String DEFAULT_BASE_URL = "https://api.marketdata.app";
  public static final String DEFAULT_API_VERSION = "v1";

  private Configuration() {}

  /**
   * Returns the first non-blank value among {@code explicit}, the named environment variable, and
   * the {@code .env} file entry — or {@code null} if none is set.
   */
  public static @Nullable String resolve(@Nullable String explicit, String envKey) {
    if (isPresent(explicit)) {
      return explicit;
    }
    String fromSystem = System.getenv(envKey);
    if (isPresent(fromSystem)) {
      return fromSystem;
    }
    String fromDotEnv = readDotEnv().get(envKey);
    return isPresent(fromDotEnv) ? fromDotEnv : null;
  }

  /**
   * Same as {@link #resolve(String, String)} but falls back to the supplied default when the
   * cascade yields nothing.
   */
  public static String resolveOrDefault(
      @Nullable String explicit, String envKey, String defaultValue) {
    String resolved = resolve(explicit, envKey);
    return resolved != null ? resolved : defaultValue;
  }

  private static boolean isPresent(@Nullable String value) {
    return value != null && !value.isBlank();
  }

  private static Map<String, String> readDotEnv() {
    Path path = Paths.get(".env");
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
