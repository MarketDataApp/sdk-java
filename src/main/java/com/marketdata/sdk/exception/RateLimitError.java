package com.marketdata.sdk.exception;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class RateLimitError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  private final @Nullable Duration retryAfter;

  public RateLimitError(String message, ErrorContext context) {
    this(message, context, null, null);
  }

  public RateLimitError(String message, ErrorContext context, @Nullable Throwable cause) {
    this(message, context, cause, null);
  }

  /**
   * Construct a rate-limit error that carries the server-specified {@code Retry-After} hint (SDK
   * requirements §9.4). RFC 6585 defines {@code Retry-After} for 429 responses; consumers can
   * inspect this value to schedule their own backoff before the next call.
   */
  public RateLimitError(
      String message,
      ErrorContext context,
      @Nullable Throwable cause,
      @Nullable Duration retryAfter) {
    super(message, context, cause);
    this.retryAfter = retryAfter;
  }

  /**
   * The value parsed from the server's {@code Retry-After} response header, when present. Empty
   * when the header was absent or the error was raised by the SDK's local preflight gate (no server
   * response).
   */
  public Optional<Duration> getRetryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
