package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RateLimitHeadersTest {

  // ---------- helpers ----------

  /**
   * Builds an immutable {@link HttpHeaders} from a flat key→value map. The JDK only exposes
   * builders via {@link java.net.http.HttpClient}; this is the canonical workaround using {@link
   * HttpHeaders#of}.
   */
  private static HttpHeaders headersOf(Map<String, String> entries) {
    Map<String, List<String>> multi = new TreeMap<>();
    entries.forEach((k, v) -> multi.put(k, List.of(v)));
    return HttpHeaders.of(multi, (a, b) -> true);
  }

  // ---------- happy path ----------

  @Test
  void parsesAllFourHeaders() {
    HttpHeaders headers =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "13"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(1000L);
    assertThat(rl.remaining()).isEqualTo(987L);
    assertThat(rl.reset()).isEqualTo(Instant.ofEpochSecond(1714867200L));
    assertThat(rl.consumed()).isEqualTo(13L);
  }

  // ---------- the all-null short-circuit ----------

  @Test
  void returnsNullWhenNoRateLimitHeadersPresent() {
    // With every header absent the long `&&` chain in `parse()` evaluates each side fully —
    // covers the "all four are null" branches.
    HttpHeaders headers = headersOf(Map.of("content-type", "application/json"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNull();
  }

  // ---------- partial headers (one present, others missing) ----------

  @Test
  void onlyLimitPresentZerosTheOthers() {
    // Covers the `null` branch of three of the four `x != null ? x : 0L` ternaries while
    // keeping `limit` non-null (the all-null short-circuit doesn't apply).
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-limit", "500"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(500L);
    assertThat(rl.remaining()).isZero();
    assertThat(rl.reset()).isEqualTo(Instant.ofEpochSecond(0L));
    assertThat(rl.consumed()).isZero();
  }

  @Test
  void onlyConsumedPresentZerosTheOthers() {
    // Covers the case where the head of the && chain is null but the tail is not — exercises
    // a different short-circuit path than onlyLimitPresent.
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-consumed", "42"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.consumed()).isEqualTo(42L);
    assertThat(rl.limit()).isZero();
  }

  @Test
  void onlyRemainingPresentExitsAtSecondCondition() {
    // Forces the && chain past `limit == null` and stops at `remaining == null`. Without this
    // test, the false-branch of the second condition is never evaluated.
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-remaining", "1234"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.remaining()).isEqualTo(1234L);
    assertThat(rl.limit()).isZero();
  }

  @Test
  void onlyResetPresentExitsAtThirdCondition() {
    // Forces the && chain past `limit` and `remaining` to evaluate `reset == null` as false.
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-reset", "1735689600"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.reset()).isEqualTo(Instant.ofEpochSecond(1735689600L));
    assertThat(rl.limit()).isZero();
    assertThat(rl.remaining()).isZero();
    assertThat(rl.consumed()).isZero();
  }

  // ---------- malformed values ----------

  @Test
  void malformedNumberIsTreatedAsAbsent() {
    // readLong's catch(NumberFormatException) returns null; the header is then treated as
    // missing. With every header malformed the result must be null, same as none-present.
    HttpHeaders headers =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "not-a-number",
                "x-api-ratelimit-remaining", "also-broken"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNull();
  }

  @Test
  void valuesAreTrimmedBeforeParsing() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-limit", "  1000  "));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(1000L);
  }

  // ---------- sanity: parse() doesn't depend on URI/method ----------

  @Test
  void parseIgnoresNonRateLimitHeaders() {
    URI dummy = URI.create("https://example/");
    HttpHeaders headers =
        headersOf(
            Map.of(
                "cf-ray", "abc",
                "content-type", "application/json",
                "x-api-ratelimit-limit", "100"));

    RateLimits rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(100L);
    assertThat(dummy).isNotNull(); // silence unused
  }
}
