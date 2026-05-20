package com.marketdata.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/**
 * Loads {@code .env} key=value pairs from disk. {@code .env} is the third tier of the configuration
 * cascade (after explicit args and env vars), and is optional — a missing file is normal and never
 * reports a warning. However, an <em>existing</em> file that the SDK fails to read is suspicious:
 * the user placed a {@code .env} expecting it to apply, and silently falling through to defaults
 * would surface later as a confusing {@code AuthenticationError} with no breadcrumb.
 *
 * <p>Warnings are collected into a caller-supplied sink rather than emitted via the SDK logger
 * directly. The loader runs inside {@link Configuration#resolve} which itself runs <em>before</em>
 * {@link MarketDataLogging#configure}, so logging from here would land on an unconfigured JUL
 * logger — wrong format, possibly invisible. {@link MarketDataClient} drains the sink after
 * configuring logging, so the breadcrumb reaches its intended destination.
 */
final class DotEnvLoader {

  /** Diagnostic emitted by the loader, replayed by {@link MarketDataClient} after logging setup. */
  record Warning(Level level, String message, @Nullable Throwable cause) {}

  /**
   * Parse {@code path} into an immutable map of {@code key → value} pairs.
   *
   * <p>{@code allowedKeys} is the allowlist: when non-null, keys outside the set are dropped during
   * parsing and never materialize in the returned map. This mirrors the defensive principle of
   * {@link EnvVars#systemLookup} — the SDK does not need to retain the consumer's unrelated secrets
   * ({@code AWS_SECRET_ACCESS_KEY}, {@code GITHUB_TOKEN}, etc.) in memory just because they
   * happened to share a {@code .env} file with our config. Passing {@code null} disables the
   * filter; that surface exists for tests that exercise the parser independently of the cascade.
   */
  static Map<String, String> load(
      Path path, Consumer<Warning> warnings, @Nullable Set<String> allowedKeys) {
    if (!Files.exists(path)) {
      return Map.of();
    }
    if (!Files.isReadable(path)) {
      warnings.accept(
          new Warning(
              Level.WARNING,
              "Found .env at "
                  + path
                  + " but it is not readable (permission denied?) — falling back to env"
                  + " vars/defaults.",
              null));
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).trim();
        if (allowedKeys != null && !allowedKeys.contains(key)) {
          continue;
        }
        String value = stripQuotes(trimmed.substring(eq + 1).trim());
        result.put(key, value);
      }
    } catch (IOException e) {
      warnings.accept(
          new Warning(
              Level.WARNING,
              "Failed to read .env at " + path + " — falling back to env vars/defaults.",
              e));
      return Map.of();
    }
    return Map.copyOf(result);
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private DotEnvLoader() {}
}
