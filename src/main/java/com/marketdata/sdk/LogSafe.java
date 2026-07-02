package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Neutralize untrusted text (API response strings such as {@code errmsg}, or malformed
 * date/timestamp cells) before it is embedded in an exception message or log line.
 *
 * <p>A hostile or buggy server controls these strings. Copied verbatim into a {@code ParseError}
 * message that a consumer then logs, embedded carriage-returns/newlines let an attacker forge or
 * split log entries, and ANSI escape sequences can spoof a terminal. This collapses every ISO
 * control character (C0/C1 range, including {@code CR}, {@code LF}, {@code TAB}, and the {@code
 * ESC} that introduces ANSI sequences) to a single space, and caps the length so a 20&nbsp;MB
 * response string (Jackson's default ceiling) can't blow up a log line.
 */
final class LogSafe {

  /** Longest untrusted fragment we echo into a message; the rest is elided. */
  static final int MAX_LEN = 200;

  private LogSafe() {}

  /**
   * Sanitize {@code raw} for safe inclusion in a log/exception message. Never returns {@code null}.
   */
  static String sanitize(@Nullable String raw) {
    if (raw == null) {
      return "";
    }
    String truncated = raw.length() > MAX_LEN ? raw.substring(0, MAX_LEN) + "…" : raw;
    StringBuilder out = new StringBuilder(truncated.length());
    for (int i = 0; i < truncated.length(); i++) {
      char c = truncated.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    return out.toString();
  }
}
