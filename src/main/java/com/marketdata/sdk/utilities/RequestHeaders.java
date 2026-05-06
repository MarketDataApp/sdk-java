package com.marketdata.sdk.utilities;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.marketdata.sdk.internal.wire.utilities.RequestHeadersDeserializer;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of the HTTP headers the API saw for the most recent {@code /headers/} request — useful
 * for debugging proxies, auth, or User-Agent issues.
 *
 * <p>Header keys are normalized to lower-case by the API; sensitive values (notably {@code
 * Authorization}) come back partially redacted. Lookups are case-insensitive.
 *
 * @param all the full set of headers; iteration order matches the API response
 */
@JsonDeserialize(using = RequestHeadersDeserializer.class)
public record RequestHeaders(Map<String, String> all) {

  /** Case-insensitive header lookup. */
  public Optional<String> get(String name) {
    return Optional.ofNullable(all.get(name.toLowerCase()));
  }

  public boolean isEmpty() {
    return all.isEmpty();
  }
}
