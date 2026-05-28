package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

public final class AuthenticationError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  public AuthenticationError(String message, ErrorContext context) {
    this(message, context, null);
  }

  public AuthenticationError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
