package com.marketdata.sdk;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

final class HttpStatusMapper {

  static @Nullable MarketDataException map(int statusCode, ErrorContext context) {
    return map(statusCode, context, null);
  }

  /**
   * Maps an HTTP status to its typed exception. When {@code retryAfter} is non-null, it is attached
   * to the resulting {@link ServerError} so the retry policy can honor §9.4. The other subtypes
   * ignore it — only server errors retry, and only retries care about Retry-After.
   */
  static @Nullable MarketDataException map(
      int statusCode, ErrorContext context, @Nullable Duration retryAfter) {
    if (statusCode >= 200 && statusCode < 300) {
      return null;
    }
    return switch (statusCode) {
      case 400 -> new BadRequestError("Bad request", context);
      case 401 -> new AuthenticationError("Authentication failed", context);
      case 404 -> new NotFoundError("Not found", context);
      case 429 -> new RateLimitError("Rate limit exceeded", context);
      case 500 -> new ServerError("Server error: 500", context, null, retryAfter);
      default -> {
        if (statusCode >= 501 && statusCode <= 599) {
          yield new ServerError("Server error: " + statusCode, context, null, retryAfter);
        }
        yield new BadRequestError("Unexpected status code: " + statusCode, context);
      }
    };
  }

  private HttpStatusMapper() {}
}
