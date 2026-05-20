package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

final class Tokens {

  private static final String REDACTED = "***…***";

  /**
   * Redact a token for log/diagnostic output. Returns {@code ***…***} alone when the token is
   * absent or short enough that exposing the trailing 4 characters would reveal most of the value
   * (length ≤ 8 — at 4 chars the suffix is the whole token; at 5–7 it's 57–80%). Only tokens with
   * &gt;8 characters get the {@code ***…***ABCD} form, which is enough material to disambiguate
   * which token is in use without leaking it.
   */
  static String redact(@Nullable String token) {
    if (token == null || token.length() <= 8) {
      return REDACTED;
    }
    return REDACTED + token.substring(token.length() - 4);
  }

  private Tokens() {}
}
