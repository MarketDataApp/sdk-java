package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.funds.FundCandlesRequest;
import com.marketdata.sdk.funds.FundResolution;
import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.ExpirationStrikes;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChain;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirations;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookup;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotes;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.OptionsStrikes;
import com.marketdata.sdk.options.OptionsStrikesRequest;
import com.marketdata.sdk.options.StrikeFilter;
import com.marketdata.sdk.options.StrikeRange;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarning;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import com.marketdata.sdk.stocks.StockResolution;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Idiomatic consumer-style examples — one short snippet per SDK resource showing
 * the typical "first call you'd write" against it.
 *
 * <p><strong>This is the growth surface for resource coverage.</strong> Each new
 * resource that lands on the SDK (stocks, options, funds, markets) gets a new
 * private {@code xxxExamples(client)} method below and a call from {@link #main}.
 * The other demos in this directory each prove one cross-cutting behavior
 * (retry, concurrency, etc.); this one shows the per-resource shape.
 *
 * <p>Hits the real API at {@code api.marketdata.app}. {@code MARKETDATA_TOKEN}
 * in the env or {@code .env} is needed for endpoints that require auth; without
 * it, the demo runs in demo mode — public endpoints succeed and the rest skip
 * with a clear note instead of crashing.
 *
 * <p>The {@code Console.*} helpers used below are demo formatting only. In a
 * real consumer app, the data lines would be plain {@code System.out.println}
 * or your logger of choice — the SDK call itself is what to copy.
 *
 * <p>Run: {@code make demo-quickstart} (or {@code ./gradlew runQuickstart}).
 */
public final class QuickstartApp {

  private QuickstartApp() {}

  public static void main(String[] args) {
    Console.header("Quickstart — idiomatic SDK usage, one section per resource");
    Console.info(
        "As stocks / options / funds / markets land on the SDK, each gets a new section below.");

    // The no-arg constructor is the idiomatic path: it reads MARKETDATA_TOKEN
    // from the env or .env, falls back to demo mode if neither is set, and
    // validates the token by firing one /user/ probe at construct time
    // (validateOnStartup=true by default). A failure here means the token is
    // invalid — caught and reported below so the rest of the demo still runs.
    try (MarketDataClient client = buildClient()) {
      if (client == null) {
        return;
      }
      utilitiesExamples(client);
      optionsExamples(client);
      stocksExamples(client);
      fundsExamples(client);
      // marketsExamples(client);   // ← add when client.markets() lands
    }
  }

  // ---------- utilities ----------

  private static void utilitiesExamples(MarketDataClient client) {
    Console.header("utilities — service health, quota, request diagnostics");

    // 1) Public endpoint: no token required. Useful as a liveness check.
    Console.step("client.utilities().status() — per-service health snapshot");
    try {
      var health = client.utilities().status();
      long online = health.values().stream().filter(ServiceStatus::online).count();
      Console.ok(online + " of " + health.values().size() + " services online");
    } catch (MarketDataException e) {
      Console.fail("status() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 2) Authenticated endpoint: returns your quota state. Catching
    // AuthenticationError is the consumer pattern for "token missing or
    // invalid" — surface a hint to the user rather than crashing.
    Console.step("client.utilities().user() — your quota & permissions");
    try {
      var me = client.utilities().user();
      User u = me.values();
      Console.ok(
          u.requestsRemaining() + " requests remaining of " + u.requestsLimit() + " (today)");
    } catch (AuthenticationError e) {
      Console.info(
          "401 — set MARKETDATA_TOKEN (env var or .env) to see your real quota."
              + " Demo mode reaches this endpoint and gets rejected, as designed.");
    } catch (MarketDataException e) {
      Console.fail("user() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 3) Diagnostic endpoint: echoes back the headers the server saw. Handy
    // when debugging "is my Authorization header actually getting through?".
    Console.step("client.utilities().headers() — what the server saw on this call");
    try {
      var echo = client.utilities().headers();
      Console.ok(
          "server received "
              + echo.values().size()
              + " request headers (Authorization echoed back redacted)");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token (same reason as utilities().user()).");
    } catch (MarketDataException e) {
      Console.fail("headers() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }
  }

  // ---------- options ----------

  /**
   * One short snippet per options endpoint, in the order a consumer typically discovers them:
   * lookup → expirations → strikes → chain → quote/quotes. The {@code chain} examples show the two
   * sealed filter groups ({@link ExpirationFilter}, {@link StrikeFilter}), the {@code
   * expiration=all} span, the optional nullable {@code rho} greek, and the {@code countback}
   * window. Options data needs entitlements, so each step catches {@link AuthenticationError}
   * separately and prints a hint — the tour stays runnable in demo mode.
   */
  private static void optionsExamples(MarketDataClient client) {
    Console.header("options — lookup, expirations, strikes, chain, quote, quotes");
    Console.info(
        "Entry point is client.options(); every endpoint takes a Builder-based request object"
            + " (no String overloads) and returns a typed MarketDataResponse (access the payload via .values()).");

    // 1) lookup — turn a human description into a well-formed OCC symbol.
    Console.step("client.options().lookup(...) — human description → OCC symbol");
    try {
      var r =
          client.options().lookup(OptionsLookupRequest.of("AAPL 1/16/2026 $200 Call"));
      Console.ok("resolved to " + r.values());
    } catch (AuthenticationError e) {
      Console.info("401 — set MARKETDATA_TOKEN (env or .env) to exercise the options endpoints.");
    } catch (MarketDataException e) {
      Console.fail("lookup() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 2) expirations — the expiration calendar for an underlying.
    Console.step("client.options().expirations(\"AAPL\") — expiration dates");
    try {
      var r =
          client.options().expirations(OptionsExpirationsRequest.of("AAPL"));
      Console.ok(
          r.values().size() + " expirations; updated " + r.updated());
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("expirations() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 3) strikes — the strike ladder, grouped per expiration.
    Console.step("client.options().strikes(\"AAPL\") — strike ladder per expiration");
    try {
      var r = client.options().strikes(OptionsStrikesRequest.of("AAPL"));
      if (r.values().isEmpty()) {
        Console.ok("no strikes returned");
      } else {
        ExpirationStrikes first = r.values().get(0);
        Console.ok(
            first.expiration().toLocalDate() + " has " + first.strikes().size() + " strikes");
      }
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("strikes() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 4) chain — the rich filter surface. The mutually-exclusive groups are sealed types: you pick
    //    one ExpirationFilter and one StrikeFilter variant, enforced by the compiler. Here: ITM-ish
    //    calls within 45 DTE, strikes 150–250, the 5 nearest the money.
    Console.step(
        "client.options().chain(...) — filtered chain via sealed ExpirationFilter / StrikeFilter");
    try {
      var r =
          client
              .options()
              .chain(
                  OptionsChainRequest.builder("AAPL")
                      .expirationFilter(ExpirationFilter.dte(45))
                      .strikeFilter(StrikeFilter.range(150, 250))
                      .side(OptionSide.CALL)
                      .strikeLimit(5)
                      .build());
      Console.ok(r.values().size() + " contracts");
      if (!r.values().isEmpty()) {
        OptionQuote q = r.values().get(0);
        // rho is an optional column — may be null when the feed omits it.
        Console.ok(q.optionSymbol() + " delta=" + q.delta() + " rho=" + q.rho());
      }
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("chain() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 5) chain with ExpirationFilter.all() — the whole chain across every expiration, distinct from
    //    omitting the filter (which the API narrows to the front-month). strikeLimit(1) keeps it small.
    Console.step("client.options().chain(... ExpirationFilter.all()) — every expiration at once");
    try {
      var chain =
          client
              .options()
              .chain(
                  OptionsChainRequest.builder("AAPL")
                      .expirationFilter(ExpirationFilter.all())
                      .side(OptionSide.CALL)
                      .strikeLimit(1)
                      .build())
              .values();
      long distinct = chain.stream().map(OptionQuote::expiration).distinct().count();
      Console.ok("spans " + distinct + " distinct expirations (front-month-only would be 1)");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("chain(all) failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 6) quote / quotes — single contract, then concurrent multi-contract fan-out. Real symbols are
    //    pulled from a tiny chain query so the contracts are guaranteed to exist. quotes returns a
    //    Map keyed by symbol; countback caps each per-symbol series to the N most recent rows.
    Console.step(
        "client.options().quote(...) / quotes(...) — single + concurrent multi-contract (countback)");
    try {
      List<OptionQuote> sample =
          client
              .options()
              .chain(
                  OptionsChainRequest.builder("AAPL")
                      .side(OptionSide.CALL)
                      .strikeRange(StrikeRange.ITM)
                      .strikeLimit(2)
                      .build())
              .values();
      if (sample.size() < 2) {
        Console.info("not enough contracts returned to demo quote/quotes — skipping");
        return;
      }
      String s1 = sample.get(0).optionSymbol();
      String s2 = sample.get(1).optionSymbol();

      var one = client.options().quote(OptionsQuoteRequest.of(s1));
      Console.ok("quote(" + s1 + ") → " + one.values().size() + " row");

      var many =
          client
              .options()
              .quotes(
                  OptionsQuotesRequest.builder(s1, s2)
                      .to(LocalDate.now())
                      .countback(5)
                      .build());
      Console.ok(
          "quotes(" + s1 + ", " + s2 + ") → " + many.size() + " symbols, <=5 rows each (countback)");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("quote/quotes failed: " + e.getExceptionType() + " — " + e.getMessage());
    }
  }

  // ---------- stocks ----------

  /**
   * One short snippet per stocks endpoint, in the order a consumer typically reaches for them:
   * candles → quote/quotes → prices → news → earnings. Entry point is {@code client.stocks()}; every
   * endpoint takes a Builder-based request and returns a typed {@code MarketDataResponse} (payload
   * via {@code .values()}). Stock data needs entitlements, so each step catches {@link
   * AuthenticationError} separately and prints a hint — the tour stays runnable in demo mode.
   */
  private static void stocksExamples(MarketDataClient client) {
    Console.header("stocks — candles, quote, quotes, prices, news, earnings");

    // 1) candles — historical OHLCV. Resolution is a value type (StockResolution.DAILY, .hours(1),
    //    .minutes(15), ...); the window is from/to (or date, or to+countback).
    Console.step("client.stocks().candles(...) — daily OHLCV for the last month");
    try {
      var r =
          client
              .stocks()
              .candles(
                  StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
                      .from(LocalDate.now().minusMonths(1))
                      .to(LocalDate.now())
                      .build());
      Console.ok(r.values().size() + " daily candles fetched");
      // §8.2: each response carries its own rate-limit snapshot (request-scoped).
      if (r.rateLimit() != null) {
        Console.info(
            "rate limit (from this response): "
                + r.rateLimit().remaining()
                + "/"
                + r.rateLimit().limit()
                + " remaining");
      }
    } catch (AuthenticationError e) {
      Console.info("401 — set MARKETDATA_TOKEN (env or .env) to exercise the stocks endpoints.");
    } catch (MarketDataException e) {
      Console.fail("candles() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 2) quote — single symbol. candle(true)/week52(true) add the opt-in OHLC / 52-week columns.
    Console.step("client.stocks().quote(\"AAPL\") — latest quote");
    try {
      var r = client.stocks().quote(StockQuoteRequest.of("AAPL"));
      if (r.values().isEmpty()) {
        Console.ok("no quote returned");
      } else {
        StockQuote q = r.values().get(0);
        Console.ok(q.symbol() + " last=" + q.last() + " bid/ask=" + q.bid() + "/" + q.ask());
      }
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("quote() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 3) quotes — multiple symbols in ONE request (the stocks backend batches a comma list), so the
    //    result is a single response with one row per symbol (NOT a per-symbol map like options).
    Console.step("client.stocks().quotes(\"AAPL\", \"MSFT\") — multi-symbol batch (single request)");
    try {
      var r = client.stocks().quotes(StockQuotesRequest.builder("AAPL", "MSFT").build());
      Console.ok(r.values().size() + " quote rows in one response");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("quotes() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 4) prices — a lighter snapshot (mid/change/updated) for several symbols, also batched.
    Console.step("client.stocks().prices(\"AAPL\", \"MSFT\") — light price snapshot");
    try {
      var r = client.stocks().prices(StockPricesRequest.of("AAPL", "MSFT"));
      Console.ok(r.values().size() + " prices fetched");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("prices() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 5) news — recent articles. The feed's latest-update time is a scalar off the response
    //    (response.updated()), distinct from each article's publicationDate.
    Console.step("client.stocks().news(\"AAPL\") — recent articles + scalar updated()");
    try {
      var r = client.stocks().news(StockNewsRequest.of("AAPL"));
      Console.ok(r.values().size() + " articles; feed updated " + r.updated());
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("news() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 6) earnings — history + forward calendar. Fundamentals/report fields are nullable on
    //    synthesized forward-quarter rows; they decode to null without error.
    Console.step("client.stocks().earnings(\"AAPL\") — EPS history (nullable forward fields)");
    try {
      var r = client.stocks().earnings(StockEarningsRequest.of("AAPL"));
      Console.ok(r.values().size() + " earnings rows");
      if (!r.values().isEmpty()) {
        StockEarning e = r.values().get(r.values().size() - 1);
        Console.ok(
            "latest: FY" + e.fiscalYear() + " Q" + e.fiscalQuarter() + " reportedEPS="
                + e.reportedEPS());
      }
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token.");
    } catch (MarketDataException e) {
      Console.fail("earnings() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }
  }

  // ---------- funds ----------

  /**
   * Funds expose a single endpoint: {@code candles}. NAV-based OHLC — no volume column, no
   * intraday resolutions ({@code FundResolution} only models daily and up), and no §12
   * auto-chunking (it only applies to intraday candles). Entry point is {@code client.funds()}.
   */
  private static void fundsExamples(MarketDataClient client) {
    Console.header("funds — candles (NAV series: OHLC, no volume)");

    Console.step("client.funds().candles(...) — daily NAV candles for the last month");
    try {
      var r =
          client
              .funds()
              .candles(
                  FundCandlesRequest.builder(FundResolution.DAILY, "VFINX")
                      .from(LocalDate.now().minusMonths(1))
                      .to(LocalDate.now())
                      .build());
      Console.ok(r.values().size() + " daily candles fetched");
      if (!r.values().isEmpty()) {
        var bar = r.values().get(r.values().size() - 1);
        Console.ok("latest: " + bar.time() + " close=" + bar.close());
      }
    } catch (AuthenticationError e) {
      Console.info("401 — set MARKETDATA_TOKEN (env or .env) to exercise the funds endpoint.");
    } catch (MarketDataException e) {
      Console.fail("candles() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }
  }

  // ---------- helpers ----------

  /**
   * Build the client. Idiomatic path is the no-arg constructor (cascade + startup
   * validation on). The fallback to the 4-arg constructor with {@code
   * validateOnStartup=false} exists so any <em>future</em> startup-probe surprise
   * (transient 5xx, slow API) doesn't kill the demo before the per-resource
   * examples run. A real consumer app would normally just let the exception
   * propagate to its top-level error handler.
   */
  private static MarketDataClient buildClient() {
    try {
      return new MarketDataClient();
    } catch (AuthenticationError e) {
      Console.fail("Constructor failed: " + e.getMessage());
      Console.info(
          "MARKETDATA_TOKEN is set but the API rejected it. Fix the token, or unset it to use"
              + " demo mode.");
      return null;
    } catch (MarketDataException e) {
      // ParseError on /user/ (payload drift), NetworkError, etc. Retry with the
      // startup probe disabled so the rest of the demo can run.
      Console.info(
          "Startup probe failed ("
              + e.getExceptionType()
              + "): "
              + e.getMessage()
              + ". Retrying with validateOnStartup=false so the demo can continue.");
      try {
        return new MarketDataClient(null, null, null, false);
      } catch (Throwable t) {
        Console.fail("Fallback construction failed: " + t.getClass().getSimpleName());
        return null;
      }
    } catch (Throwable t) {
      Console.fail("Constructor failed: " + t.getClass().getSimpleName() + " — " + t.getMessage());
      return null;
    }
  }
}
