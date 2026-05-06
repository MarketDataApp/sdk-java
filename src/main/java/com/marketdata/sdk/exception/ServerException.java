package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The API returned a 5xx response. */
public final class ServerException extends MarketDataException {

  public ServerException(String message, ErrorContext context) {
    super(message, context, null);
  }

  public ServerException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
