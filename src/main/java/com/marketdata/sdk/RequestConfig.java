package com.marketdata.sdk;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable bundle of the universal query parameters (§3) a resource carries across its endpoints.
 * A resource is a configured value: each {@code with*} method returns a copy, and {@link
 * #applyTo(RequestSpec.Builder)} writes the accumulated params onto a request being built.
 *
 * <p>Type-preserving params ({@code dateFormat}, {@code mode}, {@code limit}, {@code offset}) and
 * {@code columns} are valid on the typed path; {@code human}/{@code headers} only cohere with the
 * CSV facet (they reshape the output). The holder carries them all; each facet exposes the setters
 * that make sense there.
 */
record RequestConfig(
    @Nullable DateFormat dateFormat,
    @Nullable Mode mode,
    @Nullable Integer limit,
    @Nullable Integer offset,
    List<String> columns,
    @Nullable Boolean human,
    @Nullable Boolean headers) {

  RequestConfig {
    columns = List.copyOf(columns);
  }

  static RequestConfig empty() {
    return new RequestConfig(null, null, null, null, List.of(), null, null);
  }

  RequestConfig withDateFormat(DateFormat v) {
    return new RequestConfig(v, mode, limit, offset, columns, human, headers);
  }

  RequestConfig withMode(Mode v) {
    return new RequestConfig(dateFormat, v, limit, offset, columns, human, headers);
  }

  RequestConfig withLimit(int v) {
    return new RequestConfig(dateFormat, mode, v, offset, columns, human, headers);
  }

  RequestConfig withOffset(int v) {
    return new RequestConfig(dateFormat, mode, limit, v, columns, human, headers);
  }

  RequestConfig withColumns(List<String> v) {
    return new RequestConfig(dateFormat, mode, limit, offset, v, human, headers);
  }

  RequestConfig withHuman(boolean v) {
    return new RequestConfig(dateFormat, mode, limit, offset, columns, v, headers);
  }

  RequestConfig withHeaders(boolean v) {
    return new RequestConfig(dateFormat, mode, limit, offset, columns, human, v);
  }

  /** Writes every set universal param onto a request builder. */
  void applyTo(RequestSpec.Builder b) {
    if (dateFormat != null) {
      b.dateformat(dateFormat);
    }
    if (mode != null) {
      b.mode(mode);
    }
    if (limit != null) {
      b.limit(limit);
    }
    if (offset != null) {
      b.offset(offset);
    }
    if (!columns.isEmpty()) {
      b.columns(columns);
    }
    if (human != null) {
      b.human(human);
    }
    if (headers != null) {
      b.headers(headers);
    }
  }
}
