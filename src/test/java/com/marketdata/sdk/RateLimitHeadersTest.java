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
  void anyMissingHeaderReturnsNull() {
    // §8.2: the four x-api-ratelimit-* headers travel together on every successful response. A
    // partial response is a server-side bug — surfacing it as a snapshot with phantom zeros
    // would flip the preflight gate into a false "exhausted" state and feed consumers a
    // snapshot indistinguishable from a real one. Returning null instead lets the caller keep
    // the last-known-good snapshot.
    HttpHeaders missingRemaining =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "13"));
    HttpHeaders missingLimit =
        headersOf(
            Map.of(
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "13"));
    HttpHeaders missingReset =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-consumed", "13"));
    HttpHeaders missingConsumed =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "1714867200"));

    assertThat(RateLimitHeaders.parse(missingRemaining)).isNull();
    assertThat(RateLimitHeaders.parse(missingLimit)).isNull();
    assertThat(RateLimitHeaders.parse(missingReset)).isNull();
    assertThat(RateLimitHeaders.parse(missingConsumed)).isNull();
  }

  @Test
  void onlyOneHeaderPresentReturnsNull() {
    // The complementary check — a single header doesn't carry enough information to be useful,
    // so the all-or-nothing rule returns null whether 0 or 1 (or 2 or 3) headers are present.
    HttpHeaders onlyLimit = headersOf(Map.of("x-api-ratelimit-limit", "500"));

    assertThat(RateLimitHeaders.parse(onlyLimit)).isNull();
  }

  // ---------- malformed values ----------

  @Test
  void anyMalformedValueReturnsNull() {
    // A malformed value is treated as absent by readLong; with the all-or-nothing rule that
    // makes the whole snapshot unreliable — same outcome as the header being missing entirely.
    HttpHeaders headers =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "1000",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "not-a-number",
                "x-api-ratelimit-consumed", "13"));

    assertThat(RateLimitHeaders.parse(headers)).isNull();
  }

  @Test
  void allMalformedValuesReturnNull() {
    HttpHeaders headers =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "not-a-number",
                "x-api-ratelimit-remaining", "also-broken",
                "x-api-ratelimit-reset", "still-broken",
                "x-api-ratelimit-consumed", "broken-too"));

    assertThat(RateLimitHeaders.parse(headers)).isNull();
  }

  @Test
  void valuesAreTrimmedBeforeParsing() {
    // The complete-headers happy path; the trim guard applies to every value, exercised through
    // the limit header here.
    HttpHeaders headers =
        headersOf(
            Map.of(
                "x-api-ratelimit-limit", "  1000  ",
                "x-api-ratelimit-remaining", "987",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "13"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(1000);
  }

  @Test
  void parseIgnoresNonRateLimitHeaders() {
    // The four required headers are still present alongside unrelated ones — unrelated headers
    // must not affect parsing.
    HttpHeaders headers =
        headersOf(
            Map.of(
                "cf-ray", "abc",
                "content-type", "application/json",
                "x-api-ratelimit-limit", "100",
                "x-api-ratelimit-remaining", "99",
                "x-api-ratelimit-reset", "1714867200",
                "x-api-ratelimit-consumed", "1"));

    RateLimitSnapshot rl = RateLimitHeaders.parse(headers);

    assertThat(rl).isNotNull();
    assertThat(rl.limit()).isEqualTo(100);
  }
}
