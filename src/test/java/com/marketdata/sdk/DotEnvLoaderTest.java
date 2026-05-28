package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DotEnvLoaderTest {

  /**
   * Convenience wrapper for parser-level tests: no warning sink, no allowlist (the parser is
   * exercised independently of the cascade's allowlist).
   */
  private static Map<String, String> load(Path path) {
    return DotEnvLoader.load(path, w -> {}, null);
  }

  @Test
  void load_returns_empty_when_file_missing(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.env");

    Map<String, String> result = load(missing);

    assertThat(result).isEmpty();
  }

  @Test
  void load_missing_file_does_not_warn(@TempDir Path tmp) {
    // The cascade explicitly tolerates a missing .env — that's the common case, not an error.
    // Emitting a WARNING here would spam every consumer that runs without a .env file.
    Path missing = tmp.resolve("does-not-exist.env");
    List<DotEnvLoader.Warning> warnings = new ArrayList<>();

    DotEnvLoader.load(missing, warnings::add, null);

    assertThat(warnings).isEmpty();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS) // POSIX permissions are unreliable on Windows file systems
  void load_unreadable_file_emits_warning_and_returns_empty(@TempDir Path tmp) throws IOException {
    // Existing-but-unreadable is suspicious: the user dropped a .env expecting it to apply, but
    // the SDK can't open it. Silent fallback would surface much later as a confusing
    // AuthenticationError. Emit a Warning with the path so the breadcrumb is obvious.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    try {
      List<DotEnvLoader.Warning> warnings = new ArrayList<>();
      Map<String, String> result = DotEnvLoader.load(file, warnings::add, null);

      assertThat(result).isEmpty();
      assertThat(warnings)
          .singleElement()
          .satisfies(
              w -> {
                assertThat(w.level()).isEqualTo(Level.WARNING);
                assertThat(w.message()).contains("not readable").contains(file.toString());
                assertThat(w.cause()).isNull();
              });
    } finally {
      // Restore so @TempDir cleanup can delete the file.
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void load_io_exception_during_read_emits_warning_with_cause(@TempDir Path tmp) throws Exception {
    // Files.exists + isReadable can pass and the actual read still fail (NFS drop, decoded-bytes
    // encoding mismatch, etc.). A directory passed as the path is a portable way to make
    // Files.readAllLines blow up after the readability check succeeds.
    Path asDir = Files.createDirectory(tmp.resolve("env-as-dir"));
    List<DotEnvLoader.Warning> warnings = new ArrayList<>();

    Map<String, String> result = DotEnvLoader.load(asDir, warnings::add, null);

    assertThat(result).isEmpty();
    assertThat(warnings)
        .singleElement()
        .satisfies(
            w -> {
              assertThat(w.level()).isEqualTo(Level.WARNING);
              assertThat(w.message()).contains("Failed to read .env").contains(asDir.toString());
              assertThat(w.cause()).isNotNull();
            });
  }

  @Test
  void load_returns_empty_for_empty_file(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "");

    assertThat(load(file)).isEmpty();
  }

  @Test
  void load_parses_simple_key_value_pairs(@TempDir Path tmp) throws IOException {
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                MARKETDATA_TOKEN=abc123
                MARKETDATA_BASE_URL=https://example.com
                """);

    Map<String, String> result = load(file);

    assertThat(result)
        .containsEntry("MARKETDATA_TOKEN", "abc123")
        .containsEntry("MARKETDATA_BASE_URL", "https://example.com");
  }

  @Test
  void load_strips_double_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc 123\"\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc 123");
  }

  @Test
  void load_strips_single_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN='abc 123'\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc 123");
  }

  @Test
  void load_does_not_strip_mismatched_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc'\n");

    assertThat(load(file)).containsEntry("TOKEN", "\"abc'");
  }

  @Test
  void load_ignores_comment_lines(@TempDir Path tmp) throws IOException {
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                # a comment
                TOKEN=abc
                #TOKEN=should-be-ignored
                """);

    Map<String, String> result = load(file);

    assertThat(result).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_ignores_comment_lines_with_leading_whitespace(@TempDir Path tmp) throws IOException {
    // The parser trims each line before checking the `#` prefix, so indented full-line comments
    // (common when commenting out a block inside an aligned section) are skipped too.
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                    # leading-spaces comment
                \t# leading-tab comment
                TOKEN=abc
                """);

    assertThat(load(file)).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_ignores_blank_lines(@TempDir Path tmp) throws IOException {
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """

                TOKEN=abc

                BASE_URL=https://x

                """);

    assertThat(load(file)).containsEntry("TOKEN", "abc").containsEntry("BASE_URL", "https://x");
  }

  // ---------- inline comments ----------

  @Test
  void load_strips_inline_comment_after_whitespace(@TempDir Path tmp) throws IOException {
    // The motivating bug: `TOKEN=abc # my note` previously yielded the literal value
    // "abc # my note", which validateApiKey lets through (printable ASCII) and surfaces later
    // as a confusing AuthenticationError far from the .env file that caused it.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc123 # production token\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc123");
  }

  @Test
  void load_strips_inline_comment_after_tab(@TempDir Path tmp) throws IOException {
    // Any Unicode whitespace before `#` qualifies — tabs are common in hand-aligned .env files.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc123\t# tab-separated comment\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc123");
  }

  @Test
  void load_keeps_hash_when_not_preceded_by_whitespace(@TempDir Path tmp) throws IOException {
    // `#` adjacent to value chars is part of the value (python-dotenv / dotenv-java convention).
    // Critical for URLs with fragments and tokens that legitimately contain `#`.
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                TOKEN=abc#123
                BASE_URL=https://example.com/path#frag
                """);

    assertThat(load(file))
        .containsEntry("TOKEN", "abc#123")
        .containsEntry("BASE_URL", "https://example.com/path#frag");
  }

  @Test
  void load_keeps_hash_inside_double_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc # not a comment\"\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc # not a comment");
  }

  @Test
  void load_keeps_hash_inside_single_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN='abc # not a comment'\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc # not a comment");
  }

  @Test
  void load_strips_inline_comment_after_closing_quote(@TempDir Path tmp) throws IOException {
    // Quoted value followed by a real comment outside the quotes: the comment is stripped and the
    // quotes are removed normally.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc 123\" # the real comment\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc 123");
  }

  @Test
  void load_records_empty_value_when_value_is_blank(@TempDir Path tmp) throws IOException {
    // `KEY=` and `KEY=    ` both produce an empty-string entry. The cascade's pickFirst() treats
    // blank values as unset, so this is functionally equivalent to omitting the key — but the
    // parser still records it. Two reasons: (1) it documents the user's intent (they wrote the
    // key, so it's part of the file's shape), and (2) it keeps the parser symmetric with the
    // `KEY=#comment` case, which also yields "".
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                EMPTY_BARE=
                EMPTY_SPACES=\s\s\s
                KEPT=value
                """);

    Map<String, String> result = load(file);
    assertThat(result)
        .containsEntry("EMPTY_BARE", "")
        .containsEntry("EMPTY_SPACES", "")
        .containsEntry("KEPT", "value");
  }

  @Test
  void load_strips_inline_comment_after_closing_single_quote(@TempDir Path tmp) throws IOException {
    // Symmetry with `load_strips_inline_comment_after_closing_quote` (the double-quote variant):
    // the walk treats single and double quotes the same way, so a `#` inside `'…'` is preserved
    // and a `#` after the closing `'` with whitespace before it is a comment.
    Path file =
        Files.writeString(tmp.resolve(".env"), "TOKEN='abc # not a comment' # the real comment\n");

    assertThat(load(file)).containsEntry("TOKEN", "abc # not a comment");
  }

  @Test
  void load_strips_value_when_hash_is_first_non_whitespace_char(@TempDir Path tmp)
      throws IOException {
    // `KEY=#comment` and `KEY=   # comment` both leave an empty value. The cascade's
    // pickFirst() treats blank values as unset, so this is functionally equivalent to omitting
    // the line — the empty entry is still recorded for symmetry with `KEY=`.
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                EMPTY1=#comment-immediately
                EMPTY2=   # comment after spaces
                KEPT=value
                """);

    Map<String, String> result = load(file);
    assertThat(result)
        .containsEntry("EMPTY1", "")
        .containsEntry("EMPTY2", "")
        .containsEntry("KEPT", "value");
  }

  @Test
  void load_first_unquoted_hash_wins_over_later_ones(@TempDir Path tmp) throws IOException {
    // `value # first-comment # second` → everything from the first qualifying `#` onward is
    // comment.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=value # first-comment # second\n");

    assertThat(load(file)).containsEntry("TOKEN", "value");
  }

  @Test
  void load_keeps_hash_in_value_then_strips_later_comment(@TempDir Path tmp) throws IOException {
    // The first `#` is adjacent to value chars (not a comment); the second `#` is preceded by
    // whitespace (a comment) — only the trailing portion is stripped.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=value#part more # real-comment\n");

    assertThat(load(file)).containsEntry("TOKEN", "value#part more");
  }

  @Test
  void load_keeps_hash_outside_quotes_after_closing_quote_without_whitespace(@TempDir Path tmp)
      throws IOException {
    // `"x"#y` — `#` is preceded by the closing quote, not whitespace, so it's part of the value.
    // stripQuotes does nothing here (last char isn't a matching quote), so the literal pair-of-
    // quotes-plus-hash-tail is preserved as authored.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"x\"#y\n");

    assertThat(load(file)).containsEntry("TOKEN", "\"x\"#y");
  }

  @Test
  void load_last_assignment_wins_for_duplicate_keys(@TempDir Path tmp) throws IOException {
    // Lines are processed top-to-bottom and stored in a LinkedHashMap, so a later assignment
    // overwrites an earlier one for the same key. This documents the file as authoritative in
    // line order — useful when a user commits a base `.env` and overrides a single line at the
    // bottom for a local run.
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                TOKEN=first
                TOKEN=second
                TOKEN=third
                """);

    assertThat(load(file)).containsEntry("TOKEN", "third");
  }

  @Test
  void load_keeps_equals_signs_in_value(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=a=b=c\n");

    assertThat(load(file)).containsEntry("TOKEN", "a=b=c");
  }

  @Test
  void load_skips_lines_without_equals(@TempDir Path tmp) throws IOException {
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                this-is-not-a-pair
                TOKEN=abc
                also-not-a-pair
                """);

    assertThat(load(file)).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_skips_lines_starting_with_equals(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "=novalue\nTOKEN=abc\n");

    assertThat(load(file)).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_trims_whitespace_around_key_and_value(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "  TOKEN  =   abc   \n");

    assertThat(load(file)).containsEntry("TOKEN", "abc");
  }

  @Test
  void load_returns_immutable_map(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");

    Map<String, String> result = load(file);

    assertThat(result).isUnmodifiable();
  }

  // ---------- allowlist filter (defense for unrelated secrets) ----------

  @Test
  void load_with_allowlist_drops_keys_outside_the_set(@TempDir Path tmp) throws IOException {
    // A consumer's .env can legitimately contain secrets unrelated to the SDK (AWS creds, OAuth
    // tokens for other services, etc.). The loader must not retain those in memory just because
    // they happened to share a file — the SDK only needs the MARKETDATA_* keys it declares.
    Path file =
        Files.writeString(
            tmp.resolve(".env"),
            """
                MARKETDATA_TOKEN=abc123
                AWS_SECRET_ACCESS_KEY=sk-aws-supersecret
                GITHUB_TOKEN=ghp-leaked
                MARKETDATA_BASE_URL=https://example.com
                """);

    Map<String, String> result =
        DotEnvLoader.load(file, w -> {}, Set.of("MARKETDATA_TOKEN", "MARKETDATA_BASE_URL"));

    assertThat(result)
        .containsOnlyKeys("MARKETDATA_TOKEN", "MARKETDATA_BASE_URL")
        .containsEntry("MARKETDATA_TOKEN", "abc123")
        .containsEntry("MARKETDATA_BASE_URL", "https://example.com");
    // The disallowed values are not retained anywhere reachable from the returned map.
    assertThat(result.values()).noneMatch(v -> v.contains("supersecret") || v.contains("leaked"));
  }

  @Test
  void load_with_empty_allowlist_returns_empty(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "MARKETDATA_TOKEN=abc\n");

    Map<String, String> result = DotEnvLoader.load(file, w -> {}, Set.of());

    assertThat(result).isEmpty();
  }

  @Test
  void load_with_null_allowlist_admits_everything(@TempDir Path tmp) throws IOException {
    // Null allowlist = parser-only mode (test surface). Filtering is the caller's job — for
    // production the cascade always passes EnvVars.ALLOWED_KEYS.
    Path file = Files.writeString(tmp.resolve(".env"), "FOO=bar\nMARKETDATA_TOKEN=abc\n");

    Map<String, String> result = DotEnvLoader.load(file, w -> {}, null);

    assertThat(result).containsEntry("FOO", "bar").containsEntry("MARKETDATA_TOKEN", "abc");
  }

  @Test
  void load_successful_read_does_not_warn(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");
    List<DotEnvLoader.Warning> warnings = new ArrayList<>();

    DotEnvLoader.load(file, warnings::add, null);

    assertThat(warnings).isEmpty();
  }
}
