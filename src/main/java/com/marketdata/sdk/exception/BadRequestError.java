package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

public final class BadRequestError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  public BadRequestError(String message, ErrorContext context) {
    this(message, context, null);
  }

  public BadRequestError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
