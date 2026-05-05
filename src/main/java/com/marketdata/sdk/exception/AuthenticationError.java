package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The API rejected the credentials (HTTP 401). */
public final class AuthenticationError extends MarketDataException {

  public AuthenticationError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public AuthenticationError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
