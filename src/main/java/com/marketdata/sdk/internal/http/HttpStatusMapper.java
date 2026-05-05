package com.marketdata.sdk.internal.http;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
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
      case 400, 422 -> new BadRequestError("HTTP " + status + ": invalid request", ctx);
      case 401 -> new AuthenticationError("HTTP 401: invalid or missing API token", ctx);
      case 429 -> new RateLimitError("HTTP 429: rate limit exceeded", ctx);
      default -> new ServerError("HTTP " + status + ": server error", ctx);
    };
  }

  private static @Nullable String emptyToNull(@Nullable String s) {
    return (s == null || s.isBlank()) ? null : s;
  }
}
