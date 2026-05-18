package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

public final class ParseError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  public ParseError(String message, ErrorContext context) {
    this(message, context, null);
  }

  public ParseError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
