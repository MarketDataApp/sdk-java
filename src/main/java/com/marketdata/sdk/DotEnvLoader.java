package com.marketdata.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class DotEnvLoader {

  static Map<String, String> load(Path path) {
    if (!Files.isReadable(path)) {
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
