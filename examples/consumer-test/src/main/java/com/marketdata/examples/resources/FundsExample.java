package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import java.time.LocalDate;

/**
 * The {@code funds} resource exposes a single endpoint: {@code candles} &mdash; a mutual fund's NAV
 * series. Note there is no volume column (funds report NAV, not traded volume) and no intraday
 * resolutions ({@link FundResolution} only models daily and coarser).
 *
 * <p>Set {@code MARKETDATA_TOKEN} (env var or {@code .env}) before running. For the different date
 * windows (single day, {@code to}+{@code countback}), CSV output and column projection, see
 * {@code FundsAdvancedExample}.
 *
 * <p>Run: {@code ./gradlew runFunds}
 */
public final class FundsExample {

  private FundsExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // candles — daily NAV OHLC over a date range. Same shape as stocks/funds candles, minus volume.
      var candles = client.funds().candles(
          FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
              .from(LocalDate.now().minusWeeks(2))
              .to(LocalDate.now())
              .build());

      System.out.println("Daily NAV candles for VFINX (last two weeks):");
      for (FundCandle bar : candles.values()) {
        System.out.printf("  %s  O=%.2f H=%.2f L=%.2f C=%.2f%n",
            bar.time(), bar.open(), bar.high(), bar.low(), bar.close());
      }

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to run this example against the live API.");
    }
  }
}
