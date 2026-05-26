package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Response;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;

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
      // stocksExamples(client);    // ← add when client.stocks() lands
      // optionsExamples(client);   // ← add when client.options() lands
      // fundsExamples(client);     // ← add when client.funds() lands
      // marketsExamples(client);   // ← add when client.markets() lands
    }
  }

  // ---------- utilities ----------

  private static void utilitiesExamples(MarketDataClient client) {
    Console.header("utilities — service health, quota, request diagnostics");

    // 1) Public endpoint: no token required. Useful as a liveness check.
    Console.step("client.utilities().status() — per-service health snapshot");
    try {
      Response<ApiStatus> health = client.utilities().status();
      long online = health.data().services().stream().filter(ServiceStatus::online).count();
      Console.ok(online + " of " + health.data().services().size() + " services online");
    } catch (MarketDataException e) {
      Console.fail("status() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }

    // 2) Authenticated endpoint: returns your quota state. Catching
    // AuthenticationError is the consumer pattern for "token missing or
    // invalid" — surface a hint to the user rather than crashing.
    Console.step("client.utilities().user() — your quota & permissions");
    try {
      Response<User> me = client.utilities().user();
      User u = me.data();
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
      Response<RequestHeaders> echo = client.utilities().headers();
      Console.ok(
          "server received "
              + echo.data().headers().size()
              + " request headers (Authorization echoed back redacted)");
    } catch (AuthenticationError e) {
      Console.info("401 — needs a token (same reason as utilities().user()).");
    } catch (MarketDataException e) {
      Console.fail("headers() failed: " + e.getExceptionType() + " — " + e.getMessage());
    }
  }

  // ---------- stocks (TODO: enable when client.stocks() lands) ----------
  //
  // private static void stocksExamples(MarketDataClient client) {
  //   Console.header("stocks — quotes, candles, news");
  //
  //   Console.step("client.stocks().quote(\"AAPL\") — latest quote");
  //   var q = client.stocks().quote("AAPL");
  //   Console.ok("AAPL last=" + q.data().last() + " (asOf " + q.data().asOf() + ")");
  //
  //   Console.step("client.stocks().candles(\"AAPL\", Resolution.D, from, to) — historical OHLCV");
  //   var c = client.stocks().candles("AAPL", Resolution.D, ...);
  //   Console.ok(c.data().rows().size() + " daily candles fetched");
  // }

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
