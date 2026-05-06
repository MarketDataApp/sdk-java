package com.marketdata.sdk.internal.http;

import com.marketdata.sdk.exception.AuthenticationException;
import com.marketdata.sdk.exception.BadRequestException;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.RateLimitException;
import com.marketdata.sdk.exception.ServerException;
import org.jspecify.annotations.Nullable;

/**
 * Maps an HTTP status code to the {@link MarketDataException} subtype the SDK requirements doc §9.1
 * mandates.
 *
 * <p>Note that 200 / 203 (success) and 404 (no-data sentinel returned by the API as {@code
 * {"s":"no_data"}}) are <strong>not</strong> handled here — those status codes mean "got a body,
 * decode it" and the resource layer interprets them. This mapper only fires on hard failures.
 */
final class HttpStatusMapper {

  private HttpStatusMapper() {}

  static MarketDataException toException(
      int status, String requestUrl, @Nullable String requestId) {
    ErrorContext ctx = new ErrorContext(emptyToNull(requestId), requestUrl, status);
    return switch (status) {
      case 400, 422 -> new BadRequestException("HTTP " + status + ": invalid request", ctx);
      case 401 -> new AuthenticationException("HTTP 401: invalid or missing API token", ctx);
      case 429 -> new RateLimitException("HTTP 429: rate limit exceeded", ctx);
      default -> new ServerException("HTTP " + status + ": server error", ctx);
    };
  }

  private static @Nullable String emptyToNull(@Nullable String s) {
    return (s == null || s.isBlank()) ? null : s;
  }
}
