package com.marketdata.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * Diagnostic context attached to a {@link MarketDataException}, carrying the fields required by SDK
 * requirements §6.2.
 *
 * <p>Use {@link #empty()} for client-side errors that occur before any HTTP request is dispatched
 * (e.g. parameter validation).
 *
 * @param requestId value of the {@code cf-ray} response header, if any
 * @param requestUrl full URL of the request that produced the error
 * @param statusCode HTTP status code returned by the server
 */
public record ErrorContext(
    @Nullable String requestId, @Nullable String requestUrl, @Nullable Integer statusCode) {

  private static final ErrorContext EMPTY = new ErrorContext(null, null, null);

  public static ErrorContext empty() {
    return EMPTY;
  }
}
