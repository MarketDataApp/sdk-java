package com.marketdata.examples.common;

import com.marketdata.sdk.MarketDataClient;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every call returns a response wrapper around the typed data, with a uniform surface regardless of
 * endpoint or format. This shows what's on it: the typed payload, request metadata for logging and
 * support, format predicates, the raw body, the per-request rate limit, and {@code saveToFile}.
 *
 * <p>Uses the public {@code utilities().status()} endpoint, so it runs without a token.
 *
 * <p>Run: {@code ./gradlew runResponseFormats}
 */
public final class ResponseFormatsExample {

  private ResponseFormatsExample() {}

  public static void main(String[] args) throws Exception {
    try (MarketDataClient client = new MarketDataClient(null, null, null, false)) {

      var response = client.utilities().status();

      // The typed payload — the part you usually want.
      System.out.println("Typed data: " + response.values().size() + " services");

      // Request metadata — useful for logging and for support tickets.
      System.out.println("\nMetadata:");
      System.out.println("  statusCode = " + response.statusCode());
      System.out.println("  requestId  = " + response.requestId());
      System.out.println("  requestUrl = " + response.requestUrl());

      // Format predicates — mutually exclusive. Utility endpoints are JSON; other resources can
      // return CSV via asCsv().
      System.out.println("\nFormat: isJson=" + response.isJson()
          + " isCsv=" + response.isCsv() + " isHtml=" + response.isHtml());

      // The per-request rate limit, parsed from this response's headers (null if not present).
      if (response.rateLimit() != null) {
        System.out.println("Rate limit: " + response.rateLimit().remaining()
            + "/" + response.rateLimit().limit() + " remaining");
      }

      // The raw response body, exactly as the server sent it.
      System.out.println("\nRaw body (first 80 chars): "
          + response.json().substring(0, Math.min(80, response.json().length())) + "...");

      // saveToFile writes that raw body verbatim — handy for caching or debugging.
      Path tmp = Files.createTempFile("market-data-", ".json");
      response.saveToFile(tmp);
      System.out.println("Saved raw response to " + tmp);
      Files.deleteIfExists(tmp);

    } catch (Exception e) {
      System.out.println("Call failed: " + e.getClass().getSimpleName() + " — " + e.getMessage());
    }
  }
}
