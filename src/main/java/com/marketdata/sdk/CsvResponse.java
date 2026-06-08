package com.marketdata.sdk;

/**
 * Response from a CSV facet (e.g. {@code client.options().asCsv().chain(req)}): {@link #values()}
 * is the raw CSV text. Shared across every endpoint that supports CSV — the body is opaque text
 * with no per-endpoint structure. Distinct from {@link HtmlResponse} so the two never cross-assign.
 */
public final class CsvResponse extends AbstractMarketDataResponse<String> {

  CsvResponse(String csv, HttpResponseEnvelope envelope, Format format) {
    super(csv, envelope, format);
  }

  /** The raw CSV text (same as {@link #values()}; named for readability at call sites). */
  public String csv() {
    return values();
  }
}
