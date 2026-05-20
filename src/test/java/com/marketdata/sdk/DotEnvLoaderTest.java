package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DotEnvLoaderTest {

  /** Convenience wrapper for tests that don't care about warnings. */
  private static Map<String, String> load(Path path) {
    return DotEnvLoader.load(path, w -> {});
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

    DotEnvLoader.load(missing, warnings::add);

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
      Map<String, String> result = DotEnvLoader.load(file, warnings::add);

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

    Map<String, String> result = DotEnvLoader.load(asDir, warnings::add);

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

  @Test
  void load_successful_read_does_not_warn(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");
    List<DotEnvLoader.Warning> warnings = new ArrayList<>();

    DotEnvLoader.load(file, warnings::add);

    assertThat(warnings).isEmpty();
  }
}
