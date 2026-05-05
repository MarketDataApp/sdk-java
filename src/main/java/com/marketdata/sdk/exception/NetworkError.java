package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/** Transport-level failure: connection refused, DNS error, timeout, TLS, etc. */
public final class NetworkError extends MarketDataException {

  public NetworkError(String message, ErrorContext context) {
    super(message, context, null);
  }

  public NetworkError(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, context, cause);
  }
}
