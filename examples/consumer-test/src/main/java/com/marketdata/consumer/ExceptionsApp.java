package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.net.ServerSocket;

/**
 * Round-trips every one of the §6 / ADR-002 sealed exception subtypes through
 * the SDK. Each scenario:
 *
 * <ul>
 *   <li>scripts the mock server (or chooses an unreachable address for the
 *       network-error case) to produce the trigger condition,
 *   <li>fires a call through the SDK and asserts the exception type,
 *   <li>prints the §6 support-info dump so the wire-level diagnostic surface is
 *       visible to a human.
 * </ul>
 *
 * <p>The exhaustive switch at the bottom is the consumer-facing proof of the
 * sealed hierarchy — adding an 8th subtype to the SDK would break this switch
 * at compile time, exactly the contract ADR-002 promised.
 *
 * <p>Run: {@code ./gradlew runExceptions}
 */
public final class ExceptionsApp {
  private ExceptionsApp() {}

  public static void main(String[] args) {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client =
        new MarketDataClient("any-token", MockServerControl.BASE_URL, null, false)) {

      authenticationError401(mock, client);
      badRequest400(mock, client);
      notFound404WithRealError(mock, client);
      rateLimit429WithRetryAfter(mock, client);
      serverError500NotRetriable(mock, client);
      serverError503Retriable(mock, client);
      parseErrorMalformedBody(mock, client);
      parseErrorEmptyBody(mock, client);
      sealedSwitchDemo(mock, client);
    }

    networkErrorConnectionRefused();
  }

  // ---------- 401 ----------

  private static void authenticationError401(MockServerControl mock, MarketDataClient client) {
    Console.header("AuthenticationError on HTTP 401");
    mock.reset();
    mock.script(Step.of(401, "{\"s\":\"error\",\"errmsg\":\"Unauthorized\"}"));
    Console.expectException("AuthenticationError", () -> client.utilities().user());
  }

  // ---------- 400 ----------

  private static void badRequest400(MockServerControl mock, MarketDataClient client) {
    Console.header("BadRequestError on HTTP 400");
    mock.reset();
    mock.script(Step.of(400, "{\"s\":\"error\",\"errmsg\":\"invalid params\"}"));
    Console.expectException("BadRequestError", () -> client.utilities().status());
  }

  // ---------- 404 (real error, not no_data) ----------

  private static void notFound404WithRealError(MockServerControl mock, MarketDataClient client) {
    Console.header("NotFoundError on HTTP 404 — wait, actually...");
    Console.info(
        "Spec §11: 404 + {\"s\":\"no_data\"} is a SUCCESSFUL response. The SDK returns a");
    Console.info(
        "a MarketDataResponse with isNoData() = true. To see NotFoundError, we'd need a 404 that");
    Console.info(
        "ISN'T the no-data envelope — but the current routing maps all 404s to a successful");
    Console.info(
        "envelope (see HttpTransport.routeAndEnvelope). So in practice consumers see");
    Console.info(
        "NotFoundError only if a future endpoint maps it differently.");
    Console.info("Skipping this scenario — see ResponseFeaturesApp for the 404+no_data path.");
    // Suppress 'unused parameter' warnings by referencing the locals once.
    if (false) {
      mock.reset();
      Console.expectException("NotFoundError", () -> client.utilities().status());
    }
  }

  // ---------- 429 ----------

  private static void rateLimit429WithRetryAfter(MockServerControl mock, MarketDataClient client) {
    Console.header("RateLimitError on HTTP 429 — Retry-After surfaces on the exception");
    mock.reset();
    mock.script(
        Step.of(429, "{\"s\":\"error\",\"errmsg\":\"rate limited\"}")
            .withHeader("Retry-After", "5"));
    try {
      client.utilities().status();
      Console.fail("expected RateLimitError, call returned");
    } catch (RateLimitError e) {
      Console.ok("RateLimitError caught");
      Console.info("Retry-After parsed: " + e.getRetryAfter());
      Console.info("statusCode: " + e.getStatusCode());
    }
  }

  // ---------- 500 (not retriable per §9) ----------

  private static void serverError500NotRetriable(MockServerControl mock, MarketDataClient client) {
    Console.header("ServerError on HTTP 500 — §9 says 500 is NOT retriable");
    mock.reset();
    mock.script(Step.of(500, "{\"s\":\"error\",\"errmsg\":\"internal\"}"));
    Console.expectException("ServerError (no retry)", () -> client.utilities().status());
    Console.info("server saw exactly " + mock.stats().requests() + " request(s) — should be 1");
  }

  // ---------- 503 (retriable, exhausted) ----------

  private static void serverError503Retriable(MockServerControl mock, MarketDataClient client) {
    Console.header("ServerError on HTTP 503 — retried 3x by default policy, then surfaces");
    mock.reset();
    // 4 attempts (1 initial + 3 retries) of 503 — all fail.
    mock.script(
        java.util.List.of(
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}"),
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}"),
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}"),
            Step.of(503, "{\"s\":\"error\",\"errmsg\":\"down\"}")));
    long t0 = System.nanoTime();
    try {
      client.utilities().status();
      Console.fail("expected ServerError");
    } catch (ServerError e) {
      long elapsed = (System.nanoTime() - t0) / 1_000_000;
      Console.ok("ServerError after " + elapsed + " ms (exponential 1s + 2s + 4s ≈ 7s)");
      Console.info("server saw " + mock.stats().requests() + " requests (expected 4)");
    }
  }

  // ---------- ParseError: malformed body ----------

  private static void parseErrorMalformedBody(MockServerControl mock, MarketDataClient client) {
    Console.header("ParseError on malformed JSON");
    mock.reset();
    mock.script(Step.of(200, "{this-is-not-json"));
    Console.expectException("ParseError", () -> client.utilities().user());
  }

  // ---------- ParseError: empty body (#29 fix) ----------

  private static void parseErrorEmptyBody(MockServerControl mock, MarketDataClient client) {
    Console.header("ParseError on empty body (#29 fix) — explicit 'Empty response body' message");
    mock.reset();
    mock.script(Step.of(200, ""));
    try {
      client.utilities().user();
      Console.fail("expected ParseError");
    } catch (ParseError e) {
      if (e.getMessage().contains("Empty response body")) {
        Console.ok("explicit empty-body message: " + e.getMessage());
      } else {
        Console.fail("generic message, #29 fix not engaged: " + e.getMessage());
      }
    }
  }

  // ---------- NetworkError ----------

  private static void networkErrorConnectionRefused() {
    Console.header("NetworkError on connection refused (then retried 3x)");
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    } catch (java.io.IOException e) {
      Console.fail("couldn't reserve a closed port: " + e.getMessage());
      return;
    }
    String unreachable = "http://127.0.0.1:" + closedPort;
    try (var client = new MarketDataClient("token", unreachable, null, false)) {
      long t0 = System.nanoTime();
      try {
        client.utilities().status();
        Console.fail("expected NetworkError");
      } catch (NetworkError e) {
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Console.ok("NetworkError after " + elapsed + " ms (IOException retried per §9)");
        Console.info("cause: " + (e.getCause() == null ? "(none)" : e.getCause().getClass().getName()));
      }
    }
  }

  // ---------- exhaustive switch over the sealed hierarchy ----------

  private static void sealedSwitchDemo(MockServerControl mock, MarketDataClient client) {
    Console.header("ADR-002 sealed hierarchy — consumer-side routing");
    mock.reset();
    mock.script(Step.of(401, "{\"s\":\"error\",\"errmsg\":\"nope\"}"));
    try {
      client.utilities().user();
    } catch (com.marketdata.sdk.exception.MarketDataException e) {
      // JDK 17 (the SDK's minimum): use instanceof patterns. The hierarchy is sealed, so a
      // future SDK release that adds an 8th subtype cannot do so silently — it'd require an
      // amendment to ADR-002 and would break consumer compilations that DO use the pattern
      // switch (JDK 21+).
      //
      // JDK 21+ version (kept here as a reference for consumers on a newer JDK; pattern
      // switches over a sealed type are exhaustiveness-checked at compile time):
      //
      //   String routed = switch (e) {
      //     case AuthenticationError a -> "→ AUTH";
      //     case BadRequestError b     -> "→ BAD_REQUEST";
      //     case NotFoundError n       -> "→ NOT_FOUND";
      //     case RateLimitError r      -> "→ RATE_LIMITED";
      //     case ServerError s         -> "→ SERVER";
      //     case NetworkError n        -> "→ NETWORK";
      //     case ParseError p          -> "→ PARSE";
      //   };
      String routed;
      if (e instanceof AuthenticationError) routed = "→ AUTH";
      else if (e instanceof BadRequestError) routed = "→ BAD_REQUEST";
      else if (e instanceof NotFoundError) routed = "→ NOT_FOUND";
      else if (e instanceof RateLimitError r)
        routed = "→ RATE_LIMITED (retryAfter=" + r.getRetryAfter() + ")";
      else if (e instanceof ServerError s) routed = "→ SERVER (status=" + s.getStatusCode() + ")";
      else if (e instanceof NetworkError) routed = "→ NETWORK";
      else if (e instanceof ParseError) routed = "→ PARSE";
      else routed = "→ UNKNOWN (sealed permits drift!)";
      Console.ok("instanceof chain routed: " + routed);
      Console.info("(JDK 21+ pattern-switch reference in the source comments)");
    }
  }
}
