package com.marketdata.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads {@code .env} key=value pairs from disk. {@code .env} is the third tier of the configuration
 * cascade (after explicit args and env vars), and is optional — a missing file is normal and never
 * logs. However, an <em>existing</em> file that the SDK fails to read is suspicious: the user
 * placed a {@code .env} expecting it to apply, and silently falling through to defaults would
 * surface later as a confusing {@code AuthenticationError} with no breadcrumb. In that case we emit
 * a WARNING and still degrade to an empty map, so {@link Configuration#resolve} can fall through
 * the cascade rather than failing startup.
 */
final class DotEnvLoader {

  private static final Logger LOG = Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME);

  static Map<String, String> load(Path path) {
    if (!Files.exists(path)) {
      return Map.of();
    }
    if (!Files.isReadable(path)) {
      LOG.log(
          Level.WARNING,
          "Found .env at {0} but it is not readable (permission denied?) — falling back to env"
              + " vars/defaults.",
          path);
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
        String value = stripQuotes(trimmed.substring(eq + 1).trim());
        result.put(key, value);
      }
    } catch (IOException e) {
      LOG.log(
          Level.WARNING,
          "Failed to read .env at " + path + " — falling back to env vars/defaults.",
          e);
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
