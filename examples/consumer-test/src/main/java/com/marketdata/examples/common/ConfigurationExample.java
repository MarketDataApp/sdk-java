package com.marketdata.examples.common;

import com.marketdata.sdk.MarketDataClient;

/**
 * How to configure the client, and what the SDK validates up front.
 *
 * <p>The idiomatic path is the no-arg constructor: it reads your token and settings from a cascade
 * &mdash; explicit value &rarr; {@code MARKETDATA_*} environment variables &rarr; a {@code .env}
 * file in the working directory &rarr; built-in defaults &mdash; and validates the token by firing
 * one request when it's built. A four-arg constructor lets you set everything explicitly, which is
 * handy for tests and the snippets below.
 *
 * <p>Nothing here makes a real API call (every client uses {@code validateOnStartup=false}), so it
 * runs offline.
 *
 * <p>Run: {@code ./gradlew runConfiguration}
 */
public final class ConfigurationExample {

  private ConfigurationExample() {}

  public static void main(String[] args) {
    // The no-arg constructor is what you'd use in production:
    //
    //   try (MarketDataClient client = new MarketDataClient()) { ... }
    //
    // It resolves the token from the cascade and validates it on startup. We don't run it here
    // because it needs a token and network; the explicit constructor below shows the same settings.

    // Four-arg constructor: (apiKey, baseUrl, apiVersion, validateOnStartup). null means "use the
    // cascade / default" for that slot.
    System.out.println("=== Explicit configuration ===");
    try (var client = new MarketDataClient("my-token", "https://api.marketdata.app", "v1", false)) {
      System.out.println(client);
    }

    // Tokens are never printed in full. Long tokens show only the last 4 chars; short ones are
    // hidden entirely. This applies anywhere the SDK logs or renders the client.
    System.out.println("\n=== Token redaction ===");
    try (var client = new MarketDataClient("supersecret-token-YKT0", "https://api.marketdata.app", "v1", false)) {
      System.out.println("Long token  → " + client); // ...***…***YKT0
    }
    try (var client = new MarketDataClient("abcd", "https://api.marketdata.app", "v1", false)) {
      System.out.println("Short token → " + client); // fully hidden
    }

    // Misconfiguration fails immediately at construction with a clear message, not later on the
    // first request.
    System.out.println("\n=== Fail-fast validation ===");
    try {
      new MarketDataClient("token", "not-a-url", null, false).close();
    } catch (IllegalArgumentException e) {
      System.out.println("Bad base URL rejected at construction: " + e.getMessage());
    }
  }
}
