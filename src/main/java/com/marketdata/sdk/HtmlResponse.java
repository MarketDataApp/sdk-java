package com.marketdata.sdk;

/**
 * Response from an HTML facet: {@link #values()} is the raw HTML text. The type and the underlying
 * facet plumbing exist so enabling HTML is a trivial release once the backend supports {@code
 * format=html}; the {@code asHtml()} entry point is intentionally <em>not exposed</em> on the
 * resource today (the server returns no HTML for any data endpoint). Distinct from {@link
 * CsvResponse} so the two never cross-assign.
 */
public final class HtmlResponse extends AbstractMarketDataResponse<String> {

  HtmlResponse(String html, HttpResponseEnvelope envelope, Format format) {
    super(html, envelope, format);
  }

  /** The raw HTML text (same as {@link #values()}). */
  public String html() {
    return values();
  }
}
