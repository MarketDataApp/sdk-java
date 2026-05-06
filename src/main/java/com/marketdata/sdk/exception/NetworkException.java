package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** Transport-level failure: connection refused, DNS error, timeout, TLS, etc. */
public final class NetworkException extends MarketDataException {

  public NetworkException(String message, ErrorContext context) {
    super(message, context, null);
  }

  public NetworkException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
