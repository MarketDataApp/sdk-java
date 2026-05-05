package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The requested resource was not found (HTTP 404).
 *
 * <p>Per SDK requirements §9.1, most endpoints translate 404 into an empty no-data response rather
 * than throwing this exception. It exists for the cases where 404 truly indicates a programming
 * error.
 */
public final class NotFoundError extends MarketDataException {

  public NotFoundError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public NotFoundError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
