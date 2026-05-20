package com.marketdata.sdk;

import java.net.http.HttpHeaders;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Parses the {@code x-api-ratelimit-*} response headers that the API sets on every successful
 * request (SDK requirements §8.2) into a {@link RateLimitSnapshot}.
 *
 * <p>Returns {@code null} when the four headers do not arrive together (all absent, partial, or any
 * value unparseable). The §8.2 contract is that the four headers ship as a set; a partial delivery
 * is a server-side rate-limit-tracking outage, not legitimate data. Returning {@code null} on
 * partial responses preserves the caller's last-known-good snapshot in {@link
 * HttpTransport#latestRateLimits} instead of clobbering it with phantom zeros — those would
 * otherwise trip {@link HttpTransport#checkRateLimitPreflight} into blocking subsequent requests
 * with a fake {@code remaining=0}.
 */
final class RateLimitHeaders {

  private static final String LIMIT = "x-api-ratelimit-limit";
  private static final String REMAINING = "x-api-ratelimit-remaining";
  private static final String RESET = "x-api-ratelimit-reset";
  private static final String CONSUMED = "x-api-ratelimit-consumed";

  private RateLimitHeaders() {}

  static @Nullable RateLimitSnapshot parse(HttpHeaders headers) {
    Long limit = readLong(headers, LIMIT);
    Long remaining = readLong(headers, REMAINING);
    Long reset = readLong(headers, RESET);
    Long consumed = readLong(headers, CONSUMED);
    if (limit == null || remaining == null || reset == null || consumed == null) {
      return null;
    }
    return new RateLimitSnapshot(
        limit.intValue(), remaining.intValue(), Instant.ofEpochSecond(reset), consumed.intValue());
  }

  private static @Nullable Long readLong(HttpHeaders headers, String name) {
    return headers
        .firstValue(name)
        .map(
            v -> {
              try {
                return Long.parseLong(v.trim());
              } catch (NumberFormatException e) {
                return null;
              }
            })
        .orElse(null);
  }
}
