package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.time.LocalDate;

/**
 * The {@code markets} resource exposes a single endpoint: {@code status} &mdash; the exchange
 * open/closed calendar. (This is distinct from {@code utilities().status()}, which reports the
 * Market Data API's own service health.) Every parameter is optional.
 *
 * <p>Set {@code MARKETDATA_TOKEN} (env var or {@code .env}) before running. For the other date
 * windows, country selection, CSV output and column projection, see {@code MarketsAdvancedExample}.
 *
 * <p>Run: {@code ./gradlew runMarkets}
 */
public final class MarketsExample {

  private MarketsExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // status — was the market open on each day in a range? A bare request would return today.
      var status = client.markets().status(
          MarketStatusRequest.builder()
              .from(LocalDate.now().minusDays(7))
              .to(LocalDate.now())
              .build());

      System.out.println("Market open/closed over the last week:");
      for (MarketStatus day : status.values()) {
        System.out.println("  " + day.date().toLocalDate() + "  " + day.status()
            + (day.isOpen() ? "  (trading)" : ""));
      }

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example against the live API.");
    }
  }
}
