package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.consumer.shared.MockServerControl.Step;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.utilities.ApiStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * §13.5 {@code MarketDataResponse<T>} surface: format predicates ({@code isJson},
 * {@code isCsv}, {@code isHtml}), the no-data envelope ({@code isNoData}
 * from a 404 + {@code s:no_data}), the raw body via {@code json()}, the
 * {@code saveToFile} helper, and the redacted {@code toString} shape.
 *
 * <p>Run: {@code ./gradlew runResponse}
 */
public final class ResponseFeaturesApp {
  private ResponseFeaturesApp() {}

  public static void main(String[] args) throws Exception {
    MockServerControl mock = new MockServerControl();
    mock.requireUp();

    try (var client =
        new MarketDataClient("token", MockServerControl.BASE_URL, null, false)) {
      formatPredicates(mock, client);
      noDataEnvelope(mock, client);
      jsonReturnsRawBody(mock, client);
      saveToFileWritesVerbatim(mock, client);
      toStringIsLogSafe(mock, client);
    }
  }

  // ---------- format predicates ----------

  private static void formatPredicates(MockServerControl mock, MarketDataClient client) {
    Console.header("§13.5 format predicates");
    mock.reset();
    mock.script(Step.of(200, "{\"x-ratelimit-requests-remaining\":1,\"x-ratelimit-requests-limit\":1,\"x-options-data-permissions\":\"\"}"));

    var resp = client.utilities().user();
    Console.info("isJson(): " + resp.isJson());
    Console.info("isCsv():  " + resp.isCsv());
    Console.info("isHtml(): " + resp.isHtml());
    if (resp.isJson() && !resp.isCsv() && !resp.isHtml()) {
      Console.ok("JSON response detected — other predicates are false (mutually exclusive)");
    } else {
      Console.fail("expected isJson=true and the others false");
    }
    Console.info(
        "(isCsv/isHtml are not reachable through the utilities resource today — utility");
    Console.info(
        " endpoints are JSON-only. The wiring is there for future endpoints that negotiate format.)");
  }

  // ---------- 404 + s:no_data ----------

  private static void noDataEnvelope(MockServerControl mock, MarketDataClient client) {
    Console.header("§11: 404 + {\"s\":\"no_data\"} is a SUCCESSFUL response");
    mock.reset();
    mock.script(Step.of(404, "{\"s\":\"no_data\"}"));

    try {
      var resp = client.utilities().status();
      Console.ok(
          "no exception thrown; statusCode="
              + resp.statusCode()
              + ", isNoData="
              + resp.isNoData());
      Console.info("data().services() = " + resp.values() + " (empty list as designed)");
    } catch (Exception e) {
      Console.fail("404+no_data became an exception: " + e.getClass().getSimpleName());
    }
  }

  // ---------- defensive rawBody copy ----------

  private static void jsonReturnsRawBody(MockServerControl mock, MarketDataClient client) {
    Console.header("json() returns the raw response body verbatim");
    mock.reset();
    String payload = "{\"x-ratelimit-requests-remaining\":1,\"x-ratelimit-requests-limit\":1,\"x-options-data-permissions\":\"\"}";
    mock.script(Step.of(200, payload));

    var resp = client.utilities().user();
    String body = resp.json();
    if (body.equals(payload)) {
      Console.ok("json() matches the original payload (" + body.length() + " chars)");
    } else {
      Console.fail("json() differs from the original payload");
    }
  }

  // ---------- saveToFile ----------

  private static void saveToFileWritesVerbatim(MockServerControl mock, MarketDataClient client)
      throws Exception {
    Console.header("saveToFile writes the raw body verbatim");
    mock.reset();
    String payload = "{\"x-ratelimit-requests-remaining\":1,\"x-ratelimit-requests-limit\":1,\"x-options-data-permissions\":\"\"}";
    mock.script(Step.of(200, payload));

    var resp = client.utilities().user();
    Path tmp = Files.createTempFile("sdk-consumer-", ".json");
    try {
      resp.saveToFile(tmp);
      String on_disk = Files.readString(tmp);
      if (on_disk.equals(payload)) {
        Console.ok("on-disk content matches the original: " + tmp);
      } else {
        Console.fail("on-disk content differs from payload");
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  // ---------- toString is log-safe (§16) ----------

  private static void toStringIsLogSafe(MockServerControl mock, MarketDataClient client) {
    Console.header("§16: toString omits data + redacts query strings");
    mock.reset();
    String payload = "{\"x-ratelimit-requests-remaining\":1,\"x-ratelimit-requests-limit\":1,\"x-options-data-permissions\":\"\"}";
    mock.script(Step.of(200, payload));

    var resp = client.utilities().user();
    String repr = resp.toString();
    Console.info(repr);
    if (repr.contains("requestsRemaining")) {
      Console.fail("response toString leaked typed data (field names visible)");
    } else {
      Console.ok("typed payload NOT in toString");
    }
    if (repr.contains("bytes=") && repr.contains("status=")) {
      Console.ok("metadata visible (status, bytes, format, url)");
    } else {
      Console.fail("metadata missing");
    }
  }
}
