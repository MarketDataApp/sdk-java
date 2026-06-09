package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code stocks} — reached through {@code client.stocks().asCsv()}. Every endpoint
 * here returns a {@link CsvResponse} (opaque CSV text). Because the stock quote/price endpoints
 * batch a comma list in a single request, even the multi-symbol forms return one {@code
 * CsvResponse} (no fan-out map).
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class StocksCsvResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  StocksCsvResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  // ---------- universal + output-shaping params ----------

  public StocksCsvResource dateFormat(DateFormat v) {
    return new StocksCsvResource(transport, config.withDateFormat(v));
  }

  public StocksCsvResource mode(Mode v) {
    return new StocksCsvResource(transport, config.withMode(v));
  }

  public StocksCsvResource limit(int v) {
    return new StocksCsvResource(transport, config.withLimit(v));
  }

  public StocksCsvResource offset(int v) {
    return new StocksCsvResource(transport, config.withOffset(v));
  }

  public StocksCsvResource columns(String... v) {
    return new StocksCsvResource(transport, config.withColumns(java.util.List.of(v)));
  }

  public StocksCsvResource human(boolean v) {
    return new StocksCsvResource(transport, config.withHuman(v));
  }

  public StocksCsvResource headers(boolean v) {
    return new StocksCsvResource(transport, config.withHeaders(v));
  }

  // ---------- endpoints ----------

  public CompletableFuture<CsvResponse> candlesAsync(StockCandlesRequest request) {
    return executeCsv(StocksResource.candlesSpec(request));
  }

  public CsvResponse candles(StockCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  public CompletableFuture<CsvResponse> quoteAsync(StockQuoteRequest request) {
    return executeCsv(
        StocksResource.quoteSpec(
            request.symbol(), request.extended(), request.candle(), request.week52()));
  }

  public CsvResponse quote(StockQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  public CompletableFuture<CsvResponse> quotesAsync(StockQuotesRequest request) {
    return executeCsv(
        StocksResource.quotesSpec(
            request.symbols(), request.extended(), request.candle(), request.week52()));
  }

  public CsvResponse quotes(StockQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  public CompletableFuture<CsvResponse> pricesAsync(StockPricesRequest request) {
    return executeCsv(StocksResource.pricesSpec(request.symbols()));
  }

  public CsvResponse prices(StockPricesRequest request) {
    return transport.joinSync(pricesAsync(request));
  }

  public CompletableFuture<CsvResponse> newsAsync(StockNewsRequest request) {
    return executeCsv(StocksResource.newsSpec(request));
  }

  public CsvResponse news(StockNewsRequest request) {
    return transport.joinSync(newsAsync(request));
  }

  public CompletableFuture<CsvResponse> earningsAsync(StockEarningsRequest request) {
    return executeCsv(StocksResource.earningsSpec(request));
  }

  public CsvResponse earnings(StockEarningsRequest request) {
    return transport.joinSync(earningsAsync(request));
  }

  // ---------- execute ----------

  private CompletableFuture<CsvResponse> executeCsv(RequestSpec.Builder b) {
    config.applyTo(b);
    b.format(Format.CSV);
    RequestSpec spec = b.build();
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                new CsvResponse(
                    new String(env.body(), StandardCharsets.UTF_8), env, spec.format()));
  }
}
