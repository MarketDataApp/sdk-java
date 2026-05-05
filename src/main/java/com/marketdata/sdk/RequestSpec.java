package com.marketdata.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative description of an HTTP GET request the SDK wants to make.
 *
 * <p>Resources build instances of this and hand them to {@link HttpTransport}; the transport is the
 * only code that knows about base URLs, auth headers, timeouts, and the like.
 *
 * @param path API-relative path with no leading {@code /v1/} prefix and no trailing slash, e.g.
 *     {@code "markets/status"}. The transport adds the base URL, version prefix, and trailing
 *     slash.
 * @param queryParams ordered query parameters (insertion order preserved for predictable URLs in
 *     tests). Values are URL-encoded by the transport.
 */
record RequestSpec(String path, Map<String, String> queryParams) {

  RequestSpec {
    queryParams = Map.copyOf(queryParams);
  }

  static Builder get(String path) {
    return new Builder(path);
  }

  static final class Builder {
    private final String path;
    private final Map<String, String> queryParams = new LinkedHashMap<>();

    private Builder(String path) {
      this.path = path;
    }

    /** Adds a query parameter only if {@code value} is non-null. */
    Builder query(String key, Object value) {
      if (value != null) {
        queryParams.put(key, value.toString());
      }
      return this;
    }

    RequestSpec build() {
      return new RequestSpec(path, Collections.unmodifiableMap(queryParams));
    }
  }
}
