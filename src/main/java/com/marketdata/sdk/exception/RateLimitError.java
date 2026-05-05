package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The client exceeded the API's rate limit (HTTP 429). */
public final class RateLimitError extends MarketDataException {

  public RateLimitError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public RateLimitError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
