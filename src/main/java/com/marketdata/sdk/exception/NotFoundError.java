package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

public final class NotFoundError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  public NotFoundError(String message, ErrorContext context) {
    this(message, context, null);
  }

  public NotFoundError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
