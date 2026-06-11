package com.marketdata.sdk;

import com.marketdata.sdk.funds.FundCandlesRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * HTML facet of {@code funds}. Mirrors {@link FundsCsvResource} but returns {@link HtmlResponse}
 * and requests {@code format=html}. <strong>Not exposed to consumers</strong> — the backend serves
 * no HTML for data endpoints today, so the {@code asHtml()} entry point on {@link FundsResource} is
 * package-private. Kept built and ready so enabling HTML later is a one-line change.
 */
public final class FundsHtmlResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  FundsHtmlResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  public CompletableFuture<HtmlResponse> candlesAsync(FundCandlesRequest request) {
    return executeHtml(FundsResource.candlesSpec(request));
  }

  public HtmlResponse candles(FundCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  private CompletableFuture<HtmlResponse> executeHtml(RequestSpec.Builder b) {
    config.applyTo(b);
    b.format(Format.HTML);
    RequestSpec spec = b.build();
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                new HtmlResponse(
                    new String(env.body(), StandardCharsets.UTF_8), env, spec.format()));
  }
}
