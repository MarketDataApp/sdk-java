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
 *
 * <p>Supported syntax: {@code KEY=value} pairs, full-line {@code #} comments, blank lines, single-
 * or double-quote-wrapped values (quotes are stripped, inner whitespace preserved), and trailing
 * inline {@code # comment} markers — recognized only when the {@code #} is outside any quoted span
 * <em>and</em> preceded by whitespace (or sits at the start of the value). A {@code #} adjacent to
 * value chars stays part of the value, so URLs with fragments and tokens that contain {@code #}
 * survive intact.
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
        String afterEq = trimmed.substring(eq + 1).trim();
        String value = stripQuotes(stripInlineComment(afterEq).trim());
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

  /**
   * Strip a trailing inline comment from {@code value} if present. An inline comment is a {@code #}
   * that is (a) outside any single- or double-quoted span, and (b) preceded by whitespace or sits
   * at the very start of {@code value}. A {@code #} adjacent to value chars (e.g. {@code pa#ss},
   * {@code "https://x.example/#frag"} unquoted as {@code https://x.example/#frag}) is part of the
   * value, not a comment marker — matching python-dotenv and dotenv-java conventions, which keep
   * URLs and hash-containing tokens intact unless the author put a space before the {@code #}.
   *
   * <p>Quotes are tracked but not consumed: the wrapping quotes are still present in the returned
   * string and are stripped afterwards by {@link #stripQuotes}. The walk does not interpret escape
   * sequences, matching the existing quote handling (no {@code \"} support either).
   *
   * <p>Trailing whitespace left behind between the value and the stripped {@code #} is removed by
   * the caller's {@code trim()}.
   */
  private static String stripInlineComment(String value) {
    boolean inSingle = false;
    boolean inDouble = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (inSingle) {
        if (c == '\'') {
          inSingle = false;
        }
      } else if (inDouble) {
        if (c == '"') {
          inDouble = false;
        }
      } else if (c == '\'') {
        inSingle = true;
      } else if (c == '"') {
        inDouble = true;
      } else if (c == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
        return value.substring(0, i);
      }
    }
    return value;
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
