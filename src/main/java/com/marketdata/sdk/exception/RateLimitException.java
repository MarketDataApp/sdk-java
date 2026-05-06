package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The client exceeded the API's rate limit (HTTP 429). */
public final class RateLimitException extends MarketDataException {

  public RateLimitException(String message, ErrorContext context) {
    super(message, context, null);
  }

  public RateLimitException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
