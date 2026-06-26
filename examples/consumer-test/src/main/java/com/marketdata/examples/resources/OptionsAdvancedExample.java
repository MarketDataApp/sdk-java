package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.StrikeFilter;
import com.marketdata.sdk.options.StrikeRange;
import java.util.List;

/**
 * Less common {@code options} features, beyond {@code OptionsExample}: the rich chain filter surface
 * (sealed expiration/strike groups), multi-contract fan-out, CSV output and column projection. Runs
 * against the live API &mdash; set {@code MARKETDATA_TOKEN} with options entitlements first.
 *
 * <p>Run: {@code ./gradlew runOptionsAdvanced}
 */
public final class OptionsAdvancedExample {

  private OptionsAdvancedExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // --- Sealed filter groups ---
      // The chain has two mutually-exclusive filter axes, modeled as sealed types so the compiler
      // lets you pick exactly one variant of each: an ExpirationFilter (here: within 45 days to
      // expiry) and a StrikeFilter (here: strikes between 150 and 250).
      List<OptionQuote> chain = client.options().chain(
          OptionsChainRequest.builder("AAPL")
              .expirationFilter(ExpirationFilter.dte(45))
              .strikeFilter(StrikeFilter.range(150, 250))
              .side(OptionSide.CALL)
              .strikeRange(StrikeRange.ITM)
              .strikeLimit(5)
              .build())
          .values();
      System.out.println("Filtered chain: " + chain.size() + " contracts");

      // ExpirationFilter.all() spans every expiration at once — distinct from omitting the filter,
      // which the API narrows to the front month.
      long spans = client.options().chain(
          OptionsChainRequest.builder("AAPL")
              .expirationFilter(ExpirationFilter.all())
              .side(OptionSide.CALL)
              .strikeLimit(1)
              .build())
          .values().stream().map(OptionQuote::expiration).distinct().count();
      System.out.println("ExpirationFilter.all() spans " + spans + " distinct expirations");

      if (chain.size() < 2) {
        System.out.println("Not enough contracts to demo the rest — try a more liquid underlying.");
        return;
      }
      String s1 = chain.get(0).optionSymbol();
      String s2 = chain.get(1).optionSymbol();

      // --- Multi-contract fan-out ---
      // quotes(...) takes several OCC symbols and fires one request per symbol concurrently, so the
      // result is a Map keyed by symbol (unlike stocks.quotes, which batches into one request).
      var quotes = client.options().quotes(OptionsQuotesRequest.builder(s1, s2).build());
      quotes.forEach((sym, resp) ->
          System.out.println("  " + sym + " → " + resp.values().size() + " row(s)"));

      // --- CSV output + column projection ---
      var csv = client.options().asCsv()
          .columns("optionSymbol", "bid", "ask")
          .human(true)
          .headers(true)
          .chain(OptionsChainRequest.builder("AAPL").side(OptionSide.CALL).strikeLimit(3).build());
      System.out.println("\nCSV output:\n" + csv.csv());

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN with options entitlements to run this example.");
    }
  }
}
