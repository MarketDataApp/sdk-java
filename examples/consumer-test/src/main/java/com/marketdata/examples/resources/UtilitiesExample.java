package com.marketdata.examples.resources;

import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;

/**
 * The {@code utilities} resource: API health, your account quota, and a request-echo for debugging.
 *
 * <p>{@code status()} is public (no token); {@code user()} and {@code headers()} need a token. Set
 * {@code MARKETDATA_TOKEN} in your environment (or a {@code .env} file here) to exercise all three.
 *
 * <p>Run: {@code ./gradlew runUtilities}
 */
public final class UtilitiesExample {

  private UtilitiesExample() {}

  public static void main(String[] args) {
    try (MarketDataClient client = new MarketDataClient()) {

      // status — per-service health. Public, so it works without a token; handy as a liveness check.
      var status = client.utilities().status();
      long online = status.values().stream().filter(ServiceStatus::online).count();
      System.out.println("API health: " + online + " of " + status.values().size() + " services online");

      // user — your account: how much of your quota is left. Needs a token.
      User me = client.utilities().user().values();
      System.out.println("Quota: " + me.requestsRemaining() + " of " + me.requestsLimit() + " requests left today");

      // headers — echoes back the headers the server received. Useful to confirm your auth header
      // actually reached the API.
      var headers = client.utilities().headers().values();
      System.out.println("Server saw " + headers.size() + " request headers (Authorization echoed back redacted)");

    } catch (AuthenticationError e) {
      System.out.println("Set MARKETDATA_TOKEN (env var or .env) to call user() and headers().");
    }
  }
}
