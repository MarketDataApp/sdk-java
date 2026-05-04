package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The API returned a 5xx response. */
public final class ServerError extends MarketDataException {

  public ServerError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public ServerError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
