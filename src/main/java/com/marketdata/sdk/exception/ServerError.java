package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

public final class ServerError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  public ServerError(String message, ErrorContext context) {
    this(message, context, null);
  }

  public ServerError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
