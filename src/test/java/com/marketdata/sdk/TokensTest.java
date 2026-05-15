package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokensTest {

  private static final String REDACTED = "***…***";

  @Test
  void redact_returns_marker_for_null() {
    assertThat(Tokens.redact(null)).isEqualTo(REDACTED);
  }

  @Test
  void redact_returns_marker_for_empty_string() {
    assertThat(Tokens.redact("")).isEqualTo(REDACTED);
  }

  @Test
  void redact_returns_marker_for_tokens_shorter_than_four_chars() {
    assertThat(Tokens.redact("a")).isEqualTo(REDACTED);
    assertThat(Tokens.redact("ab")).isEqualTo(REDACTED);
    assertThat(Tokens.redact("abc")).isEqualTo(REDACTED);
  }

  @Test
  void redact_appends_full_token_when_exactly_four_chars() {
    assertThat(Tokens.redact("abcd")).isEqualTo(REDACTED + "abcd");
  }

  @Test
  void redact_appends_last_four_chars_for_normal_token() {
    assertThat(Tokens.redact("MARKETDATA_TOKEN_VALUE_YKT0")).isEqualTo(REDACTED + "YKT0");
  }

  @Test
  void redact_never_contains_token_prefix_for_normal_token() {
    String token = "supersecrettoken1234567890";

    String redacted = Tokens.redact(token);

    assertThat(redacted).doesNotContain("supersecret");
    assertThat(redacted).doesNotContain("token12345");
    assertThat(redacted).endsWith("7890");
  }

  @Test
  void redact_handles_tokens_with_special_characters() {
    assertThat(Tokens.redact("abc.def-ghi/jklMNOP")).isEqualTo(REDACTED + "MNOP");
  }

  @Test
  void redact_handles_unicode_token() {
    assertThat(Tokens.redact("token-ñöùéABCD")).isEqualTo(REDACTED + "ABCD");
  }

  @Test
  void redact_returns_marker_unchanged_for_blank_strings_shorter_than_four() {
    assertThat(Tokens.redact("   ")).isEqualTo(REDACTED);
  }
}
