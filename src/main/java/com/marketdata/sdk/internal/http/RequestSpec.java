package com.marketdata.sdk.internal.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative description of an HTTP GET request the SDK wants to make.
 *
 * <p>Resources build instances of this and hand them to {@link HttpTransport}; the transport is the
 * only code that knows about base URLs, auth headers, timeouts, and the like.
 *
 * <p>Paths come in two flavors. The default ({@link #get}) is <em>API-versioned</em>: the transport
 * prepends the configured API version (e.g. {@code /v1/}). The {@link #getAtRoot} variant produces
 * a path directly under {@code baseUrl} — used by the handful of utility endpoints that sit outside
 * the version-prefixed surface (e.g. {@code /status/}, {@code /headers/}).
 *
 * @param path path segment with no leading slash and no trailing slash, e.g. {@code
 *     "markets/status"} or {@code "status"}. The transport adds the rest.
 * @param queryParams ordered query parameters (insertion order preserved for predictable URLs in
 *     tests). Values are URL-encoded by the transport.
 * @param versioned whether to prepend the configured API version to the path (default true)
 */
public record RequestSpec(String path, Map<String, String> queryParams, boolean versioned) {

  public RequestSpec {
    // Preserve insertion order — Map.copyOf would defensively copy but
    // strip the iteration order, which breaks predictable URLs in tests
    // and in any caller that cares about query-param order on the wire.
    queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
  }

  /** Builds a request against the API-versioned surface (default — most endpoints). */
  public static Builder get(String path) {
    return new Builder(path, true);
  }

  /**
   * Builds a request against the root surface, bypassing the version prefix. Used for the small set
   * of utility endpoints documented under {@code https://api.marketdata.app/<path>/} rather than
   * {@code /v1/<path>/}.
   */
  public static Builder getAtRoot(String path) {
    return new Builder(path, false);
  }

  public static final class Builder {
    private final String path;
    private final boolean versioned;
    private final Map<String, String> queryParams = new LinkedHashMap<>();

    private Builder(String path, boolean versioned) {
      this.path = path;
      this.versioned = versioned;
    }

    /** Adds a query parameter only if {@code value} is non-null. */
    public Builder query(String key, Object value) {
      if (value != null) {
        queryParams.put(key, value.toString());
      }
      return this;
    }

    public RequestSpec build() {
      return new RequestSpec(path, Collections.unmodifiableMap(queryParams), versioned);
    }
  }
}
