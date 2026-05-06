package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The API rejected the credentials (HTTP 401). */
public final class AuthenticationException extends MarketDataException {

  public AuthenticationException(String message, ErrorContext context) {
    super(message, context, null);
  }

  public AuthenticationException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
