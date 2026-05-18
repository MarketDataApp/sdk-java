package com.marketdata.sdk.exception;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ErrorContext(
    @Nullable String requestId, String requestUrl, int statusCode, Instant timestamp) {

  public static ErrorContext forResponse(
      String requestUrl, int statusCode, @Nullable String requestId, Instant timestamp) {
    return new ErrorContext(requestId, requestUrl, statusCode, timestamp);
  }

  public static ErrorContext forNoResponse(String requestUrl, Instant timestamp) {
    return new ErrorContext(null, requestUrl, 0, timestamp);
  }
}
