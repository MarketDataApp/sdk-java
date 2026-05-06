package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** The request was malformed or invalid (HTTP 400 / 422). */
public final class BadRequestException extends MarketDataException {

  public BadRequestException(String message, ErrorContext context) {
    super(message, context, null);
  }

  public BadRequestException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
