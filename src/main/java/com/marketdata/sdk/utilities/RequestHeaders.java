package com.marketdata.sdk.utilities;

import java.util.Map;
import java.util.Objects;

/**
 * Response shape for {@code GET /headers/} — the request headers echoed back by the server (with
 * sensitive values like {@code Authorization} redacted server-side).
 *
 * <p>Used for diagnosing auth and routing issues; the SDK does not interpret the contents, it just
 * surfaces them.
 *
 * @param headers all headers the server received, lower-cased keys to values. The map is
 *     defensively copied and immutable. Never {@code null} — the package is {@code @NullMarked},
 *     and the canonical constructor rejects a {@code null} argument with a {@link
 *     NullPointerException} naming the field. The wire-format deserializer pre-checks for a JSON
 *     {@code null} token and surfaces a {@link com.marketdata.sdk.exception.ParseError} instead, so
 *     consumers never see a bare NPE from the wire path.
 */
public record RequestHeaders(Map<String, String> headers) {

  public RequestHeaders {
    Objects.requireNonNull(headers, "headers");
    headers = Map.copyOf(headers);
  }
}
