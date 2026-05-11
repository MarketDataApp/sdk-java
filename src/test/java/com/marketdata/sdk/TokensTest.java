package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokensTest {

  @Test
  void redactsLongTokenKeepingLastFourChars() {
    String redacted = Tokens.redact("0123456789abcdefghijklmnopqrstuvwxyzYKT0");
    assertThat(redacted).endsWith("YKT0");
    assertThat(redacted).matches("\\*+YKT0");
    assertThat(redacted).hasSize(40);
  }

  @Test
  void padsShortTokensToMinimumMaskLength() {
    // 10-char token: 4 visible, 6 hidden — but mask floor is 32.
    String redacted = Tokens.redact("ABCDEF1234");
    assertThat(redacted).endsWith("1234");
    assertThat(redacted).hasSize(36); // 32 asterisks + 4 visible
  }

  @Test
  void tokenShorterThanFourCharsIsFullyMasked() {
    assertThat(Tokens.redact("abc")).isEqualTo("***");
  }

  @Test
  void blankOrNullTokenRendersAsNone() {
    assertThat(Tokens.redact(null)).isEqualTo("(none)");
    assertThat(Tokens.redact("")).isEqualTo("(none)");
    assertThat(Tokens.redact("   ")).isEqualTo("(none)");
  }
}
