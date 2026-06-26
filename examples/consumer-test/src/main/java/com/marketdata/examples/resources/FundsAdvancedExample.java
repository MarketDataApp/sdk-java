package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import java.time.LocalDate;

/**
 * Less common {@code funds} features, beyond {@code FundsExample}: the different date windows, CSV
 * output and column projection. Runs against the live API &mdash; set {@code MARKETDATA_TOKEN} first.
 *
 * <p>Run: {@code ./gradlew runFundsAdvanced}
 */
public final class FundsAdvancedExample {

  private FundsAdvancedExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // --- Single trading day ---
      // date(...) is mutually exclusive with from/to/countback: ask for one specific session.
      var oneDay = client.funds().candles(
          FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
              .date(LocalDate.now().minusDays(7))
              .build());
      System.out.println("Single day: " + oneDay.values().size() + " candle(s)");

      // --- Last N candles (countback), weekly resolution ---
      var weekly = client.funds().candles(
          FundCandlesRequest.builder(FundResolution.WEEKLY, "VFINX")
              .to(LocalDate.now())
              .countback(8)
              .build());
      System.out.println("Last 8 weekly NAV candles:");
      for (FundCandle bar : weekly.values()) {
        System.out.println("  " + bar.time() + "  close=" + bar.close());
      }

      // --- CSV output + column projection ---
      // Project to just the date and close columns; human(true) gives readable header names, and
      // dateFormat(TIMESTAMP) renders the date column as readable timestamps instead of unix epochs.
      var csv = client.funds().asCsv()
          .dateFormat(com.marketdata.sdk.DateFormat.TIMESTAMP)
          .columns("t", "c")
          .human(true)
          .headers(true)
          .candles(FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
              .to(LocalDate.now())
              .countback(5)
              .build());
      System.out.println("\nCSV output:\n" + csv.csv());

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example.");
    }
  }
}
