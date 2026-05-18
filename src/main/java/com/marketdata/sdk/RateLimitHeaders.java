package com.marketdata.sdk;

import java.net.http.HttpHeaders;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Parses the {@code x-api-ratelimit-*} response headers that the API sets on every successful
 * request (SDK requirements §8.2) into a {@link RateLimitSnapshot}.
 *
 * <p>Returns {@code null} when none of the relevant headers are present, which happens during a
 * rate-limit-tracking outage on the server side (the API silently swallows the error and keeps
 * serving the request).
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
    if (limit == null && remaining == null && reset == null && consumed == null) {
      return null;
    }
    return new RateLimitSnapshot(
        limit != null ? limit.intValue() : 0,
        remaining != null ? remaining.intValue() : 0,
        Instant.ofEpochSecond(reset != null ? reset : 0L),
        consumed != null ? consumed.intValue() : 0);
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
