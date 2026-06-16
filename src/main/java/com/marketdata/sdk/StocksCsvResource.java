package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    List<StocksResource.DateRange> chunks = StocksResource.candleChunks(request);
    if (chunks.size() == 1) {
      StocksResource.DateRange only = chunks.get(0);
      return executeCsv(StocksResource.candlesSpec(request, only.from(), only.to()));
    }
    // §12 auto-chunking on the CSV facet: fan out a CSV sub-request per year-sized slice and merge
    // the texts (dropping the repeated header row from every slice after the first when headers are
    // on), so the consumer gets one continuous CSV instead of a silently truncated first year.
    boolean headersIncluded = !Boolean.FALSE.equals(config.headers());
    List<CompletableFuture<EnvBody>> futures = new ArrayList<>(chunks.size());
    for (StocksResource.DateRange range : chunks) {
      RequestSpec.Builder b = StocksResource.candlesSpec(request, range.from(), range.to());
      config.applyTo(b);
      b.format(Format.CSV);
      RequestSpec spec = b.build();
      futures.add(
          transport
              .executeAsync(spec)
              .thenApply(
                  env ->
                      new EnvBody(
                          new String(env.body(), StandardCharsets.UTF_8), env, spec.format())));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            unused -> {
              EnvBody last = futures.get(futures.size() - 1).join();
              List<String> bodies = new ArrayList<>(futures.size());
              for (CompletableFuture<EnvBody> f : futures) {
                bodies.add(f.join().body());
              }
              return new CsvResponse(
                  mergeCsvBodies(bodies, headersIncluded), last.envelope(), last.format());
            });
  }

  private record EnvBody(String body, HttpResponseEnvelope envelope, Format format) {}

  /**
   * Concatenates CSV slice bodies in order. When {@code headersIncluded}, the leading header line
   * of every slice after the first is dropped so the merged text has a single header.
   */
  static String mergeCsvBodies(List<String> bodies, boolean headersIncluded) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bodies.size(); i++) {
      String body = bodies.get(i);
      if (i > 0 && headersIncluded) {
        int nl = body.indexOf('\n');
        body = nl >= 0 ? body.substring(nl + 1) : ""; // drop the repeated header row
      }
      if (body.isEmpty()) {
        continue;
      }
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
        sb.append('\n');
      }
      sb.append(body);
    }
    return sb.toString();
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
