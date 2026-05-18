package com.marketdata.sdk.exception;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class ServerError extends MarketDataException {

  private static final long serialVersionUID = 1L;

  private final @Nullable Duration retryAfter;

  public ServerError(String message, ErrorContext context) {
    this(message, context, null, null);
  }

  public ServerError(String message, ErrorContext context, @Nullable Throwable cause) {
    this(message, context, cause, null);
  }

  /**
   * Construct a server error that carries the server-specified {@code Retry-After} hint (SDK
   * requirements §9.4). When present, the retry policy uses this value instead of the calculated
   * exponential backoff before the next attempt.
   */
  public ServerError(
      String message,
      ErrorContext context,
      @Nullable Throwable cause,
      @Nullable Duration retryAfter) {
    super(message, context, cause);
    this.retryAfter = retryAfter;
  }

  /**
   * The value parsed from the server's {@code Retry-After} response header, when present. Otherwise
   * empty (the policy falls back to its calculated backoff).
   */
  public Optional<Duration> getRetryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
