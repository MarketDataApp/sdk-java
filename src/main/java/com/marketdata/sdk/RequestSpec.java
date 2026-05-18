package com.marketdata.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative description of an HTTP GET request the SDK wants to make.
 *
 * <p>Resources build instances of this and hand them to {@link HttpTransport}; the transport is the
 * only code that knows about base URLs, auth headers, timeouts, and the like. The transport stays
 * agnostic to response format — the {@code format} field is what tells it which {@code Accept}
 * header to send (a courtesy; {@code ?format=} on the query string is the source of truth, since
 * that is the path exercised by the backend's own test suite).
 *
 * <p>Universal query parameters (per SDK requirements §3) are surfaced as typed builder methods so
 * resources don't reach for {@link Builder#query} for the common cross-cutting cases.
 *
 * @param path API-relative path with no leading {@code /v1/} prefix and no trailing slash, e.g.
 *     {@code "markets/status"}. The transport adds the base URL, version prefix (when {@link
 *     #versioned} is true), and trailing slash.
 * @param queryParams ordered query parameters (insertion order preserved for predictable URLs in
 *     tests). Values are URL-encoded by the transport.
 * @param format wire response format. The transport mirrors this in the {@code Accept} request
 *     header; the {@code ?format=} query param is also written into {@code queryParams} by the
 *     builder when set.
 * @param versioned when true, the transport interpolates the API version segment between base URL
 *     and path (the default, used by every {@code /v1/...} endpoint); when false, the path is
 *     appended directly to the base URL. The handful of system endpoints documented at the API root
 *     — {@code /status/}, {@code /headers/} — opt into the unversioned form.
 */
record RequestSpec(String path, Map<String, String> queryParams, Format format, boolean versioned) {

  static final Format DEFAULT_FORMAT = Format.JSON;

  RequestSpec {
    // Preserve insertion order — Map.copyOf would defensively copy but
    // strip the iteration order, which breaks predictable URLs in tests
    // and in any caller that cares about query-param order on the wire.
    queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
  }

  static Builder get(String path) {
    return new Builder(path);
  }

  static final class Builder {
    private final String path;
    private final Map<String, String> queryParams = new LinkedHashMap<>();
    private Format format = DEFAULT_FORMAT;
    private boolean versioned = true;

    private Builder(String path) {
      this.path = path;
    }

    /** Adds an arbitrary query parameter; skipped if {@code value} is null. */
    Builder query(String key, Object value) {
      if (value != null) {
        queryParams.put(key, value.toString());
      }
      return this;
    }

    /**
     * Marks this request as targeting the unversioned root of the API ({@code
     * https://api.marketdata.app/path/}), rather than the default {@code
     * https://api.marketdata.app/v1/path/}. Only a handful of system endpoints — {@code /status/}
     * and {@code /headers/} — live there.
     */
    Builder unversioned() {
      this.versioned = false;
      return this;
    }

    /** Sets the wire response format ({@code ?format=}) and the matching Accept header. */
    Builder format(Format format) {
      this.format = format;
      queryParams.put("format", format.wireValue());
      return this;
    }

    /** Sets {@code ?dateformat=} controlling date/time serialization. */
    Builder dateformat(DateFormat fmt) {
      queryParams.put("dateformat", fmt.wireValue());
      return this;
    }

    /** Sets {@code ?mode=} controlling data freshness tier. */
    Builder mode(Mode mode) {
      queryParams.put("mode", mode.wireValue());
      return this;
    }

    /** Sets {@code ?headers=true|false} controlling the CSV header row. */
    Builder headers(boolean include) {
      queryParams.put("headers", String.valueOf(include));
      return this;
    }

    /** Sets {@code ?human=true|false} for human-readable attribute names. */
    Builder human(boolean human) {
      queryParams.put("human", String.valueOf(human));
      return this;
    }

    /** Sets {@code ?columns=...} as a comma-joined list. No-op when {@code cols} is empty. */
    Builder columns(List<String> cols) {
      if (!cols.isEmpty()) {
        queryParams.put("columns", String.join(",", cols));
      }
      return this;
    }

    /** Sets {@code ?limit=}. */
    Builder limit(int limit) {
      queryParams.put("limit", String.valueOf(limit));
      return this;
    }

    /** Sets {@code ?offset=}. */
    Builder offset(int offset) {
      queryParams.put("offset", String.valueOf(offset));
      return this;
    }

    RequestSpec build() {
      // Pass the raw LinkedHashMap — the record's compact constructor defensively copies and
      // wraps it as unmodifiable, so wrapping here too would just rebuild a redundant view.
      return new RequestSpec(path, queryParams, format, versioned);
    }
  }
}
