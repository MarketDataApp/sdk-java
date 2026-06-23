package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import java.util.List;

/**
 * The first calls you'd write against the {@code options} resource: resolve a symbol, list
 * expirations, pull a filtered chain, and quote a single contract. Options data needs an entitled
 * token; set {@code MARKETDATA_TOKEN} (env var or {@code .env}) before running.
 *
 * <p>For the full chain filter surface (DTE/strike/liquidity filters), multi-contract fan-out, CSV
 * output and column projection, see {@code OptionsAdvancedExample}.
 *
 * <p>Run: {@code ./gradlew runOptions}
 */
public final class OptionsExample {

  private OptionsExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // lookup — turn a human description into a well-formed OCC option symbol.
      String occSymbol = client.options()
          .lookup(OptionsLookupRequest.of("AAPL 1/16/2026 $200 Call"))
          .values();
      System.out.println("Lookup resolved to: " + occSymbol);

      // expirations — the expiration calendar for an underlying.
      var expirations = client.options().expirations(OptionsExpirationsRequest.of("AAPL"));
      System.out.println("AAPL has " + expirations.values().size() + " expiration dates");

      // chain — the option chain, with a couple of common filters: calls only, the 5 nearest the
      // money. The chain is where most option workflows start.
      List<OptionQuote> chain = client.options().chain(
          OptionsChainRequest.builder("AAPL")
              .side(OptionSide.CALL)
              .strikeLimit(5)
              .build())
          .values();
      System.out.println("\nChain (5 calls nearest the money):");
      for (OptionQuote c : chain) {
        System.out.println("  " + c.optionSymbol()
            + "  strike=" + c.strike() + "  bid/ask=" + c.bid() + "/" + c.ask() + "  delta=" + c.delta());
      }

      // quote — a single contract. Use a real symbol pulled from the chain above.
      if (!chain.isEmpty()) {
        String symbol = chain.get(0).optionSymbol();
        OptionQuote q = client.options().quote(OptionsQuoteRequest.of(symbol)).values().get(0);
        System.out.println("\nQuote " + q.optionSymbol() + ": last=" + q.last() + "  iv=" + q.iv());
      }

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN with options entitlements to run this example.");
    }
  }
}
