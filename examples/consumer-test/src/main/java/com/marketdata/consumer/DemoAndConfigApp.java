package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.consumer.shared.MockServerControl;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.exception.AuthenticationError;

/**
 * Configuration cascade, demo mode, validation, and the §16 token-redaction
 * promises — all things that fire at construction time, before any request is
 * made.
 *
 * <p>Some scenarios use the mock server (start it first: {@code cd
 * ../mock-server && ./run.sh}) so the live API can't accidentally
 * influence the outcome.
 *
 * <p>Run: {@code ./gradlew runDemoConfig}
 */
public final class DemoAndConfigApp {
  private DemoAndConfigApp() {}

  public static void main(String[] args) {
    new MockServerControl().requireUp();

    demoModeNoToken();
    tokenRedactionShort();
    tokenRedactionLong();
    explicitOverridesEnv();
    invalidBaseUrlFailsAtConstruct();
    invalidApiKeyCrlfFailsAtConstruct();
    validateOnStartupSucceedsAgainstMockServer();
    validateOnStartupFailsOn401();
  }

  // ---------- demo mode ----------

  private static void demoModeNoToken() {
    Console.header("Demo mode: no token → demoMode=true, validateOnStartup is a no-op");
    // The §4 cascade resolves the token from explicit → MARKETDATA_TOKEN env → .env → null.
    // If any earlier rung populated a token (the local .env in this repo always does), the
    // 4-arg constructor with apiKey=null still picks it up — that's correct cascade behavior,
    // but it means "demo mode" can only be observed when ALL upstream sources are empty.
    if (anyTokenSourcePopulated()) {
      Console.info(
          "Skipping live demo-mode construction: a token is available somewhere in the cascade");
      Console.info(
          "(env var MARKETDATA_TOKEN and/or .env file), so the 4-arg ctor with apiKey=null still");
      Console.info(
          "resolves a real token. To see demo mode live: unset the env var AND remove .env, then");
      Console.info("re-run this app.");
      Console.info("");
      Console.info("Static verification of demo mode's existence:");
      Console.info(
          "  - MarketDataClient.toString() prints `demoMode=true` when Configuration.apiKey() is null");
      Console.info(
          "  - runStartupValidation() short-circuits in demo mode (see DemoMode.isDemo)");
      return;
    }
    try (var client = new MarketDataClient(null, MockServerControl.BASE_URL, null, true)) {
      Console.info(client.toString());
      Console.ok("constructor succeeded — demo mode skipped the /user/ probe");
    }
  }

  /** True if either the env var or a readable {@code .env} in CWD contains MARKETDATA_TOKEN. */
  private static boolean anyTokenSourcePopulated() {
    String envValue = System.getenv("MARKETDATA_TOKEN");
    if (envValue != null && !envValue.isBlank()) {
      return true;
    }
    java.nio.file.Path dotEnv = java.nio.file.Path.of(".env");
    if (java.nio.file.Files.isReadable(dotEnv)) {
      try {
        for (String line : java.nio.file.Files.readAllLines(dotEnv)) {
          if (line.trim().startsWith("MARKETDATA_TOKEN=")) {
            String value = line.substring(line.indexOf('=') + 1).trim();
            if (!value.isEmpty() && !value.equals("\"\"")) {
              return true;
            }
          }
        }
      } catch (java.io.IOException ignored) {
        // Treat unreadable .env as "no token there".
      }
    }
    return false;
  }

  // ---------- §16 token redaction ----------

  private static void tokenRedactionShort() {
    Console.header("§16: short tokens (≤8 chars) redact entirely — no last-4 leak");
    try (var client = new MarketDataClient("abcd", MockServerControl.BASE_URL, null, false)) {
      String repr = client.toString();
      Console.info(repr);
      if (repr.contains("abcd")) {
        Console.fail("token leaked into toString — expected ***…*** alone");
      } else {
        Console.ok("token fully redacted (length 4 ≤ 8)");
      }
    }
  }

  private static void tokenRedactionLong() {
    Console.header("§16: tokens > 8 chars show the trailing 4");
    try (var client =
        new MarketDataClient(
            "supersecret-token-YKT0", MockServerControl.BASE_URL, null, false)) {
      String repr = client.toString();
      Console.info(repr);
      if (repr.contains("supersecret") || repr.contains("token-")) {
        Console.fail("token prefix leaked");
      } else if (!repr.contains("YKT0")) {
        Console.fail("trailing 4 missing — expected ***…***YKT0");
      } else {
        Console.ok("redacted as ***…***YKT0 — enough to disambiguate, not enough to use");
      }
    }
  }

  // ---------- §4 configuration cascade ----------

  private static void explicitOverridesEnv() {
    Console.header("§4 cascade: explicit constructor args win over env / .env");
    String explicitUrl = MockServerControl.BASE_URL;
    try (var client = new MarketDataClient("any-token", explicitUrl, "v1", false)) {
      String repr = client.toString();
      if (!repr.contains("baseUrl=" + explicitUrl)) {
        Console.fail("explicit baseUrl not honored: " + repr);
      } else {
        Console.ok("explicit baseUrl applied: " + explicitUrl);
      }
    }
  }

  // ---------- early-fail validation ----------

  private static void invalidBaseUrlFailsAtConstruct() {
    Console.header("Validation: malformed baseUrl fails at construct, not at first request");
    try {
      new MarketDataClient("token", "not-a-url", null, false).close();
      Console.fail("constructor returned for a baseUrl that isn't even a URL");
    } catch (IllegalArgumentException e) {
      Console.ok("IAE at construct: " + e.getMessage());
    }
  }

  private static void invalidApiKeyCrlfFailsAtConstruct() {
    Console.header("Validation: API key with CRLF rejected at construct (§23 fix)");
    try {
      new MarketDataClient("good-prefix\rinjected", MockServerControl.BASE_URL, null, false).close();
      Console.fail("constructor accepted an API key containing CR");
    } catch (IllegalArgumentException e) {
      Console.ok("IAE at construct: " + e.getMessage());
      if (e.getMessage().contains("good-prefix") || e.getMessage().contains("injected")) {
        Console.fail("token leaked into IAE message — §16 violation");
      } else {
        Console.ok("token NOT echoed in the message — §16 honored");
      }
    }
  }

  // ---------- §5 validateOnStartup ----------

  private static void validateOnStartupSucceedsAgainstMockServer() {
    Console.header("§5: validateOnStartup=true → /user/ probe on construct (mock returns 200)");
    new MockServerControl().reset();
    try (var client =
        new MarketDataClient("any-token", MockServerControl.BASE_URL, null, true)) {
      Console.ok("constructor returned — probe succeeded");
      Console.info("rateLimits captured from /user/ response: " + client.getRateLimits());
    }
  }

  private static void validateOnStartupFailsOn401() {
    Console.header("§5: validateOnStartup=true + 401 on /user/ → AuthenticationError at construct");
    MockServerControl mock = new MockServerControl();
    mock.reset();
    mock.script(
        MockServerControl.Step.of(
                401, "{\"s\":\"error\",\"errmsg\":\"Unauthorized\"}")
            .forPath("/user/"));
    Console.info("server queue before construct: " + mock.stats().requests() + " requests, scripted step queued");
    try {
      new MarketDataClient("bad-token", MockServerControl.BASE_URL, null, true).close();
      Console.fail("constructor returned despite 401 on /user/");
      Console.info("server stats after: " + mock.stats().requests() + " requests");
    } catch (AuthenticationError e) {
      Console.ok("AuthenticationError at construct: " + e.getMessage());
      Console.info("statusCode: " + e.getStatusCode() + ", requestId: " + e.getRequestId());
    } catch (Throwable t) {
      Console.fail(
          "unexpected throwable type: " + t.getClass().getName() + " — " + t.getMessage());
      Console.info("server stats after: " + mock.stats().requests() + " requests");
    }
  }
}
