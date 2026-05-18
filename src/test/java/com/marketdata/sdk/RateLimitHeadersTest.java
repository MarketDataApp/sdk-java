package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RateLimitHeadersTest {

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

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(1000);
    assertThat(rl.remaining()).isEqualTo(987);
    assertThat(rl.reset()).isEqualTo(Instant.ofEpochSecond(1714867200L));
    assertThat(rl.consumed()).isEqualTo(13);
  }

  // ---------- the all-null short-circuit ----------

  @Test
  void returnsNullWhenNoRateLimitHeadersPresent() {
    HttpHeaders headers = headersOf(Map.of("content-type", "application/json"));

    assertThat(RateLimitHeaders.parse(headers)).isNull();
  }

  // ---------- partial headers ----------

  @Test
  void onlyLimitPresentZerosTheOthers() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-limit", "500"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(500);
    assertThat(rl.remaining()).isZero();
    assertThat(rl.reset()).isEqualTo(Instant.ofEpochSecond(0L));
    assertThat(rl.consumed()).isZero();
  }

  @Test
  void onlyConsumedPresentZerosTheOthers() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-consumed", "42"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.consumed()).isEqualTo(42);
    assertThat(rl.limit()).isZero();
  }

  @Test
  void onlyRemainingPresent() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-remaining", "1234"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.remaining()).isEqualTo(1234);
    assertThat(rl.limit()).isZero();
  }

  @Test
  void onlyResetPresent() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-reset", "1735689600"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

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

    assertThat(RateLimitHeaders.parse(headers)).isNull();
  }

  @Test
  void valuesAreTrimmedBeforeParsing() {
    HttpHeaders headers = headersOf(Map.of("x-api-ratelimit-limit", "  1000  "));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(1000);
  }

  @Test
  void parseIgnoresNonRateLimitHeaders() {
    HttpHeaders headers =
        headersOf(
            Map.of(
                "cf-ray", "abc",
                "content-type", "application/json",
                "x-api-ratelimit-limit", "100"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(100);
  }
}
