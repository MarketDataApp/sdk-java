package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvLoaderTest {

  @Test
  void load_returns_empty_when_file_missing(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.env");

    Map<String, String> result = DotEnvLoader.load(missing);

    assertThat(result).isEmpty();
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
}
