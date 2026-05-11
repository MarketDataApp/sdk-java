package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Token redaction helpers. SDK requirements §5 / §16: API tokens must never appear in log output
 * verbatim.
 */
final class Tokens {

  /**
   * Minimum number of asterisks emitted before the trailing 4 chars, matching the SDK requirements
   * §7 example ({@code ************************************YKT0}).
   */
  private static final int MIN_MASK_LENGTH = 32;

  private static final int VISIBLE_TAIL = 4;

  private Tokens() {}

  /**
   * Returns a redacted form of {@code token} suitable for logging. The last 4 characters are
   * preserved; the rest is replaced with asterisks padded to at least {@value #MIN_MASK_LENGTH}
   * characters.
   */
  public static String redact(@Nullable String token) {
    if (token == null || token.isBlank()) {
      return "(none)";
    }
    if (token.length() <= VISIBLE_TAIL) {
      return "*".repeat(token.length());
    }
    int hidden = Math.max(token.length() - VISIBLE_TAIL, MIN_MASK_LENGTH);
    return "*".repeat(hidden) + token.substring(token.length() - VISIBLE_TAIL);
  }
}
