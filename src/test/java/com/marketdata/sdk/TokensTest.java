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

  /**
   * Issue #24: tokens of length ≤ 8 are fully redacted — emitting the last 4 chars would expose
   * 50%–100% of the value. Sandbox/demo keys are exactly this short, and the SDK promises (§16)
   * never to log a token verbatim. Above 8 chars the trailing 4 give consumers enough material to
   * disambiguate which key is loaded without revealing it.
   */
  @Test
  void redact_returns_marker_only_for_tokens_eight_or_shorter() {
    assertThat(Tokens.redact("abcd")).isEqualTo(REDACTED); // len=4: would have been 100% leak
    assertThat(Tokens.redact("abcde")).isEqualTo(REDACTED); // 80% leak
    assertThat(Tokens.redact("abcdef")).isEqualTo(REDACTED); // 67%
    assertThat(Tokens.redact("abcdefg")).isEqualTo(REDACTED); // 57%
    assertThat(Tokens.redact("abcdefgh")).isEqualTo(REDACTED); // 50%
  }

  @Test
  void redact_appends_last_four_only_above_length_eight() {
    // Boundary: length 9 is the first that gets the trailing-4 form.
    assertThat(Tokens.redact("abcdefghi")).isEqualTo(REDACTED + "fghi");
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
