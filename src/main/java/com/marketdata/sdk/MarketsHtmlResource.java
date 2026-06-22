package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatusRequest;
import java.util.concurrent.CompletableFuture;

/**
 * HTML facet of {@code markets}. Mirrors {@link MarketsCsvResource} but returns {@link
 * HtmlResponse} and requests {@code format=html}. <strong>Not exposed to consumers</strong> — the
 * backend serves no HTML for data endpoints today, so the {@code asHtml()} entry point on {@link
 * MarketsResource} is package-private. Kept built and ready so enabling HTML later is a one-line
 * change.
 */
public final class MarketsHtmlResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  MarketsHtmlResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  public CompletableFuture<HtmlResponse> statusAsync(MarketStatusRequest request) {
    return executeHtml(MarketsResource.statusSpec(request));
  }

  public HtmlResponse status(MarketStatusRequest request) {
    return transport.joinSync(statusAsync(request));
  }

  private CompletableFuture<HtmlResponse> executeHtml(RequestSpec.Builder b) {
    return TextResponses.execute(transport, config, b, Format.HTML, HtmlResponse::new);
  }
}
