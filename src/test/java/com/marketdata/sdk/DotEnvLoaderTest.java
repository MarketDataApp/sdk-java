package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DotEnvLoaderTest {

  private CapturingHandler handler;

  @BeforeEach
  void attachLogHandler() {
    handler = new CapturingHandler();
    handler.setLevel(Level.ALL);
    Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME).addHandler(handler);
  }

  @AfterEach
  void detachLogHandler() {
    Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME).removeHandler(handler);
  }

  @Test
  void load_returns_empty_when_file_missing(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.env");

    Map<String, String> result = DotEnvLoader.load(missing);

    assertThat(result).isEmpty();
  }

  @Test
  void load_missing_file_does_not_log(@TempDir Path tmp) {
    // The cascade explicitly tolerates a missing .env — that's the common case, not an error.
    // Emitting a WARNING here would spam every consumer that runs without a .env file.
    Path missing = tmp.resolve("does-not-exist.env");

    DotEnvLoader.load(missing);

    assertThat(handler.records).isEmpty();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS) // POSIX permissions are unreliable on Windows file systems
  void load_unreadable_file_emits_warning_and_returns_empty(@TempDir Path tmp) throws IOException {
    // Existing-but-unreadable is suspicious: the user dropped a .env expecting it to apply, but
    // the SDK can't open it. Silent fallback would surface much later as a confusing
    // AuthenticationError. Log a WARNING with the path so the breadcrumb is obvious.
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    try {
      Map<String, String> result = DotEnvLoader.load(file);

      assertThat(result).isEmpty();
      assertThat(handler.records)
          .singleElement()
          .satisfies(
              r -> {
                assertThat(r.getLevel()).isEqualTo(Level.WARNING);
                assertThat(handler.formatLast()).contains("not readable").contains(file.toString());
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

    Map<String, String> result = DotEnvLoader.load(asDir);

    assertThat(result).isEmpty();
    assertThat(handler.records)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.getLevel()).isEqualTo(Level.WARNING);
              assertThat(r.getThrown()).isNotNull();
              assertThat(handler.formatLast())
                  .contains("Failed to read .env")
                  .contains(asDir.toString());
            });
  }

  @Test
  void load_returns_empty_for_empty_file(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "");

    assertThat(DotEnvLoader.load(file)).isEmpty();
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

    Map<String, String> result = DotEnvLoader.load(file);

    assertThat(result)
        .containsEntry("MARKETDATA_TOKEN", "abc123")
        .containsEntry("MARKETDATA_BASE_URL", "https://example.com");
  }

  @Test
  void load_strips_double_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc 123\"\n");

    assertThat(DotEnvLoader.load(file)).containsEntry("TOKEN", "abc 123");
  }

  @Test
  void load_strips_single_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN='abc 123'\n");

    assertThat(DotEnvLoader.load(file)).containsEntry("TOKEN", "abc 123");
  }

  @Test
  void load_does_not_strip_mismatched_quotes(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=\"abc'\n");

    assertThat(DotEnvLoader.load(file)).containsEntry("TOKEN", "\"abc'");
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

    Map<String, String> result = DotEnvLoader.load(file);

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

    assertThat(DotEnvLoader.load(file))
        .containsEntry("TOKEN", "abc")
        .containsEntry("BASE_URL", "https://x");
  }

  @Test
  void load_keeps_equals_signs_in_value(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=a=b=c\n");

    assertThat(DotEnvLoader.load(file)).containsEntry("TOKEN", "a=b=c");
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

    assertThat(DotEnvLoader.load(file)).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_skips_lines_starting_with_equals(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "=novalue\nTOKEN=abc\n");

    assertThat(DotEnvLoader.load(file)).containsExactlyEntriesOf(Map.of("TOKEN", "abc"));
  }

  @Test
  void load_trims_whitespace_around_key_and_value(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "  TOKEN  =   abc   \n");

    assertThat(DotEnvLoader.load(file)).containsEntry("TOKEN", "abc");
  }

  @Test
  void load_returns_immutable_map(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");

    Map<String, String> result = DotEnvLoader.load(file);

    assertThat(result).isUnmodifiable();
  }

  @Test
  void load_successful_read_does_not_log(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve(".env"), "TOKEN=abc\n");

    DotEnvLoader.load(file);

    assertThat(handler.records).isEmpty();
  }

  /** Captures {@link LogRecord}s emitted on the SDK logger so tests can assert on them. */
  private static final class CapturingHandler extends Handler {
    private final java.util.List<LogRecord> records = new java.util.ArrayList<>();
    private final java.util.logging.Formatter fmt = new java.util.logging.SimpleFormatter();

    @Override
    public void publish(LogRecord r) {
      records.add(r);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    String formatLast() {
      return fmt.format(records.get(records.size() - 1));
    }
  }
}
