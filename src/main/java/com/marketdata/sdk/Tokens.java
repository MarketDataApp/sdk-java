package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

final class Tokens {

  private static final String REDACTED = "***…***";

  static String redact(@Nullable String token) {
    if (token == null || token.length() < 4) {
      return REDACTED;
    }
    return REDACTED + token.substring(token.length() - 4);
  }

  private Tokens() {}
}
