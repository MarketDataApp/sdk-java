package com.marketdata.examples.resources;

import com.marketdata.sdk.DateFormat;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Mode;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
import java.time.LocalDate;

/**
 * Less common {@code stocks} features, beyond {@code StocksExample}: universal parameters, the
 * different candle windows, CSV output, column projection, and the per-response rate limit. Runs
 * against the live API &mdash; set {@code MARKETDATA_TOKEN} first.
 *
 * <p>Run: {@code ./gradlew runStocksAdvanced}
 */
public final class StocksAdvancedExample {

  private StocksAdvancedExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // --- Universal parameters ---
      // dateFormat / mode / limit / offset apply to any endpoint. They're set on the resource
      // (client.stocks()) before the call. dateFormat is type-preserving (it changes how dates are
      // sent on the wire, not the Java type you get back); mode selects live vs delayed data. (For
      // candles the result size comes from the from/to/countback window, not from limit.)
      var candles = client.stocks()
          .dateFormat(DateFormat.TIMESTAMP)
          .mode(Mode.DELAYED)
          .candles(StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
              .from(LocalDate.now().minusMonths(1))
              .to(LocalDate.now())
              .build());
      System.out.println("Candles with universal params applied: " + candles.values().size());

      // --- Candle window by countback ---
      // Instead of from/to, ask for the last N candles ending at a date — no left edge needed.
      var lastTen = client.stocks().candles(
          StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
              .to(LocalDate.now())
              .countback(10)
              .build());
      System.out.println("Last 10 sessions (countback): " + lastTen.values().size() + " candles");

      // --- Column projection ---
      // Ask the API for only the columns you need. Fields you didn't request come back null — no
      // error. Lighter payloads when you only want a couple of fields.
      StockQuote projected = client.stocks()
          .columns("symbol", "last")
          .quote(StockQuoteRequest.of("AAPL"))
          .values().get(0);
      System.out.println("Projected quote: symbol=" + projected.symbol()
          + " last=" + projected.last() + " (bid not requested → " + projected.bid() + ")");

      // --- CSV output ---
      // asCsv() switches the whole resource to CSV. The response exposes the raw CSV text. The
      // CSV-only shaping params (human-readable headers, header row, column order) live here too.
      var csv = client.stocks().asCsv()
          .columns("symbol", "last")
          .human(true)
          .headers(true)
          .quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());
      System.out.println("\nCSV output:\n" + csv.csv());

      // --- Per-response rate limit ---
      // Every response carries the rate-limit snapshot from its own headers (request-scoped),
      // distinct from client.getRateLimits() which is the client-wide latest.
      var quote = client.stocks().quote(StockQuoteRequest.of("AAPL"));
      if (quote.rateLimit() != null) {
        System.out.println("\nThis request's rate limit: "
            + quote.rateLimit().remaining() + "/" + quote.rateLimit().limit() + " remaining");
      }

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example.");
    }
  }
}
