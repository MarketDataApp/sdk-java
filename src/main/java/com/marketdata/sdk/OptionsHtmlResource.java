package com.marketdata.sdk;

import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import java.util.concurrent.CompletableFuture;

/**
 * HTML facet of {@code options}. Mirrors {@link OptionsCsvResource} but returns {@link
 * HtmlResponse} and requests {@code format=html}. <strong>Not exposed to consumers</strong> — the
 * backend serves no HTML for data endpoints today, so the {@code asHtml()} entry point on {@link
 * OptionsResource} is package-private. Kept built and ready so enabling HTML later is a one-line
 * change (flip the entry point to {@code public}).
 */
public final class OptionsHtmlResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  OptionsHtmlResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  public CompletableFuture<HtmlResponse> chainAsync(OptionsChainRequest request) {
    return executeHtml(OptionsResource.chainSpec(request));
  }

  public HtmlResponse chain(OptionsChainRequest request) {
    return transport.joinSync(chainAsync(request));
  }

  public CompletableFuture<HtmlResponse> quoteAsync(OptionsQuoteRequest request) {
    return executeHtml(
        OptionsResource.quoteSpec(
            request.optionSymbol(),
            request.date(),
            request.from(),
            request.to(),
            request.countback()));
  }

  public HtmlResponse quote(OptionsQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  public CompletableFuture<HtmlResponse> expirationsAsync(OptionsExpirationsRequest request) {
    return executeHtml(OptionsResource.expirationsSpec(request));
  }

  public HtmlResponse expirations(OptionsExpirationsRequest request) {
    return transport.joinSync(expirationsAsync(request));
  }

  private CompletableFuture<HtmlResponse> executeHtml(RequestSpec.Builder b) {
    return TextResponses.execute(transport, config, b, Format.HTML, HtmlResponse::new);
  }
}
