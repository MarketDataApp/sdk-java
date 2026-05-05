package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The API response could not be decoded into the expected model. */
public final class ParseError extends MarketDataException {

  public ParseError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public ParseError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
