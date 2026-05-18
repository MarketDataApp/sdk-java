package com.marketdata.sdk.utilities;

import java.util.Map;

/**
 * Response shape for {@code GET /headers/} — the request headers echoed back by the server (with
 * sensitive values like {@code Authorization} redacted server-side).
 *
 * <p>Used for diagnosing auth and routing issues; the SDK does not interpret the contents, it just
 * surfaces them.
 *
 * @param headers all headers the server received, lower-cased keys to values. The map is
 *     defensively copied and immutable.
 */
public record RequestHeaders(Map<String, String> headers) {

  public RequestHeaders {
    headers = Map.copyOf(headers);
  }
}
