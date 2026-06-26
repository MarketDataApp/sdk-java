package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.time.LocalDate;

/**
 * Less common {@code markets} features, beyond {@code MarketsExample}: the single-day and countback
 * windows, country selection, CSV output and column projection. Runs against the live API &mdash;
 * set {@code MARKETDATA_TOKEN} first.
 *
 * <p>Run: {@code ./gradlew runMarketsAdvanced}
 */
public final class MarketsAdvancedExample {

  private MarketsAdvancedExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // --- Was the market open on a specific day? ---
      var oneDay = client.markets().status(
          MarketStatusRequest.builder().date(LocalDate.now().minusDays(3)).build());
      MarketStatus day = oneDay.values().get(0);
      System.out.println(day.date().toLocalDate() + " was " + (day.isOpen() ? "open" : "closed"));

      // --- Last N days (countback) for a specific country's calendar ---
      var counted = client.markets().status(
          MarketStatusRequest.builder()
              .country("US")
              .to(LocalDate.now())
              .countback(10)
              .build());
      long open = counted.values().stream().filter(MarketStatus::isOpen).count();
      System.out.println("Last 10 calendar days: " + open + " open of " + counted.values().size());

      // --- CSV output + column projection ---
      // dateFormat(TIMESTAMP) renders the date column as readable dates instead of unix epochs.
      var csv = client.markets().asCsv()
          .dateFormat(com.marketdata.sdk.DateFormat.TIMESTAMP)
          .columns("date", "status")
          .human(true)
          .headers(true)
          .status(MarketStatusRequest.builder()
              .from(LocalDate.now().minusDays(7))
              .to(LocalDate.now())
              .build());
      System.out.println("\nCSV output:\n" + csv.csv());

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example.");
    }
  }
}
