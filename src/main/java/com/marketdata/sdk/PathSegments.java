package com.marketdata.sdk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Percent-encode user-provided segments before they are appended to a {@link RequestSpec} path.
 * {@link HttpTransport#buildUri} writes the path verbatim (it only encodes query components, where
 * {@code application/x-www-form-urlencoded} is the right dialect); when a resource places an
 * unsanitized value in the path — symbol names with spaces, OCC option descriptions with {@code $},
 * etc. — it must encode beforehand or the resulting URL goes through {@code URI.create} as an
 * illegal-character failure.
 *
 * <p>Slashes are preserved verbatim because some endpoints accept multi-segment user input that
 * naturally contains them (e.g. {@code /options/lookup/(?P<userInput>.*)} matches across slashes,
 * and dates like {@code "7/26/23"} look natural). Spaces become {@code %20} (not {@code +}, which
 * is the {@code form-urlencoded} dialect and would be read literally by strict path parsers).
 */
final class PathSegments {

  private PathSegments() {}

  /** Percent-encode {@code raw} for path-context use, preserving {@code /} as a separator. */
  static String encode(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    int start = 0;
    for (int i = 0; i < raw.length(); i++) {
      if (raw.charAt(i) == '/') {
        if (i > start) {
          out.append(URLEncoder.encode(raw.substring(start, i), StandardCharsets.UTF_8));
        }
        out.append('/');
        start = i + 1;
      }
    }
    if (start < raw.length()) {
      out.append(URLEncoder.encode(raw.substring(start), StandardCharsets.UTF_8));
    }
    return out.toString().replace("+", "%20");
  }
}
