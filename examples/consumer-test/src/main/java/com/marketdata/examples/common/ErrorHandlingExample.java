package com.marketdata.examples.common;

import com.marketdata.examples.util.MockServer;
import com.marketdata.examples.util.MockServer.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;

/**
 * How errors surface, and how to handle them.
 *
 * <p>Everything the SDK throws is a {@link MarketDataException}. It's a <em>sealed</em> hierarchy:
 * the seven subtypes below are the complete set, so you can branch on them exhaustively and the
 * compiler will tell you if a future version adds one. Each carries support context
 * ({@code getStatusCode()}, {@code getRequestId()}, {@code getSupportInfo()}, &hellip;).
 *
 * <p>This example scripts a local server to produce a couple of error conditions on demand. Start
 * the mock first: {@code cd ../mock-server && ./run.sh}.
 *
 * <p>Run: {@code ./gradlew runErrors}
 */
public final class ErrorHandlingExample {

  private ErrorHandlingExample() {}

  public static void main(String[] args) {
    MockServer mock = new MockServer();
    mock.requireUp();

    try (var client = new MarketDataClient("token", MockServer.BASE_URL, null, false)) {

      // Catch a specific subtype when you want to react to one condition — e.g. a bad token.
      System.out.println("=== Catching one specific error ===");
      mock.script(Step.of(401, "{\"s\":\"error\",\"errmsg\":\"Unauthorized\"}"));
      try {
        client.utilities().user();
      } catch (AuthenticationError e) {
        System.out.println("Got AuthenticationError (HTTP " + e.getStatusCode() + "): " + e.getMessage());
      }

      // Or branch over the whole hierarchy. Because it's sealed, this covers every case the SDK can
      // throw. (On JDK 21+ you can write this as an exhaustive `switch` pattern — see the comment.)
      System.out.println("\n=== Routing by type ===");
      mock.script(Step.of(429, "{\"s\":\"error\",\"errmsg\":\"rate limited\"}").header("Retry-After", "5"));
      try {
        client.utilities().status();
      } catch (MarketDataException e) {
        System.out.println(route(e));
        System.out.println("\nSupport context for a bug report:\n" + e.getSupportInfo().stripTrailing());
      }
    }
  }

  // JDK 21+ equivalent, exhaustiveness-checked by the compiler over the sealed type:
  //
  //   return switch (e) {
  //     case AuthenticationError a -> "Authentication failed — check your token";
  //     case BadRequestError b     -> "Bad request — check your parameters";
  //     case NotFoundError n       -> "Not found";
  //     case RateLimitError r      -> "Rate limited — retry after " + retryHint(r);
  //     case ServerError s         -> "Server error (HTTP " + s.getStatusCode() + ")";
  //     case NetworkError n        -> "Network problem — is the API reachable?";
  //     case ParseError p          -> "Could not parse the response";
  //   };
  private static String route(MarketDataException e) {
    if (e instanceof AuthenticationError) {
      return "Authentication failed — check your token";
    } else if (e instanceof BadRequestError) {
      return "Bad request — check your parameters";
    } else if (e instanceof NotFoundError) {
      return "Not found";
    } else if (e instanceof RateLimitError r) {
      return "Rate limited — retry after " + retryHint(r);
    } else if (e instanceof ServerError s) {
      return "Server error (HTTP " + s.getStatusCode() + ")";
    } else if (e instanceof NetworkError) {
      return "Network problem — is the API reachable?";
    } else if (e instanceof ParseError) {
      return "Could not parse the response";
    }
    return "Unknown error";
  }

  /** getRetryAfter() is an Optional<Duration> — render it as seconds, or a fallback if absent. */
  private static String retryHint(RateLimitError e) {
    return e.getRetryAfter().map(d -> d.toSeconds() + "s").orElse("a moment");
  }
}
