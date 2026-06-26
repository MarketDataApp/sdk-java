package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import java.util.concurrent.CompletableFuture;

/**
 * HTML facet of {@code stocks}. Mirrors {@link StocksCsvResource} but returns {@link HtmlResponse}
 * and requests {@code format=html}. <strong>Not exposed to consumers</strong> — the backend serves
 * no HTML for data endpoints today, so the {@code asHtml()} entry point on {@link StocksResource}
 * is package-private. Kept built and ready so enabling HTML later is a one-line change.
 */
public final class StocksHtmlResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  StocksHtmlResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  public CompletableFuture<HtmlResponse> candlesAsync(StockCandlesRequest request) {
    return executeHtml(StocksResource.candlesSpec(request));
  }

  public HtmlResponse candles(StockCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  public CompletableFuture<HtmlResponse> quoteAsync(StockQuoteRequest request) {
    return executeHtml(
        StocksResource.quoteSpec(
            request.symbol(), request.extended(), request.candle(), request.week52()));
  }

  public HtmlResponse quote(StockQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  public CompletableFuture<HtmlResponse> quotesAsync(StockQuotesRequest request) {
    return executeHtml(
        StocksResource.quotesSpec(
            request.symbols(), request.extended(), request.candle(), request.week52()));
  }

  public HtmlResponse quotes(StockQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  public CompletableFuture<HtmlResponse> pricesAsync(StockPricesRequest request) {
    return executeHtml(StocksResource.pricesSpec(request.symbols()));
  }

  public HtmlResponse prices(StockPricesRequest request) {
    return transport.joinSync(pricesAsync(request));
  }

  public CompletableFuture<HtmlResponse> newsAsync(StockNewsRequest request) {
    return executeHtml(StocksResource.newsSpec(request));
  }

  public HtmlResponse news(StockNewsRequest request) {
    return transport.joinSync(newsAsync(request));
  }

  public CompletableFuture<HtmlResponse> earningsAsync(StockEarningsRequest request) {
    return executeHtml(StocksResource.earningsSpec(request));
  }

  public HtmlResponse earnings(StockEarningsRequest request) {
    return transport.joinSync(earningsAsync(request));
  }

  private CompletableFuture<HtmlResponse> executeHtml(RequestSpec.Builder b) {
    return TextResponses.execute(transport, config, b, Format.HTML, HtmlResponse::new);
  }
}
