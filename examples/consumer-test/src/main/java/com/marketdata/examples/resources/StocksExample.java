package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.stocks.StockCandle;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
import java.time.LocalDate;

/**
 * The first calls you'd write against the {@code stocks} resource: a candle series, a single quote,
 * and a multi-symbol batch. Runs against the live API, so set {@code MARKETDATA_TOKEN} in your
 * environment (or a {@code .env} file in this directory) before running.
 *
 * <p>For less common parameters (column projection, CSV/HTML output, candle windows by countback)
 * see {@code StocksAdvancedExample}.
 *
 * <p>Run: {@code ./gradlew runStocks}
 */
public final class StocksExample {

  private StocksExample() {}

  public static void main(String[] args) {
    // The no-arg constructor reads your token from the environment (or .env), then validates it.
    try (MarketDataClient client = new MarketDataClient()) {

      // candles — historical OHLCV. The resolution is a value type (DAILY, hours(1), minutes(15));
      // the window is a from/to date range.
      var candles = client.stocks().candles(
          StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
              .from(LocalDate.now().minusWeeks(1))
              .to(LocalDate.now())
              .build());
      System.out.println("Daily candles for AAPL (last week):");
      for (StockCandle bar : candles.values()) {
        System.out.printf("  %s  O=%.2f H=%.2f L=%.2f C=%.2f V=%d%n",
            bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
      }

      // quote — the latest quote for one symbol. quote(...) returns a list; a single symbol is row 0.
      StockQuote q = client.stocks().quote(StockQuoteRequest.of("AAPL")).values().get(0);
      System.out.printf("%nQuote: %s  last=%.2f  bid/ask=%.2f/%.2f%n",
          q.symbol(), q.last(), q.bid(), q.ask());

      // quotes — several symbols in ONE request. The stocks backend batches a comma list, so the
      // result is a single response with one row per symbol.
      var quotes = client.stocks().quotes(StockQuotesRequest.builder("AAPL", "MSFT", "GOOGL").build());
      System.out.println("\nBatch quotes (one request):");
      for (StockQuote row : quotes.values()) {
        System.out.printf("  %-6s last=%.2f%n", row.symbol(), row.last());
      }

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example against the live API.");
    }
  }
}
