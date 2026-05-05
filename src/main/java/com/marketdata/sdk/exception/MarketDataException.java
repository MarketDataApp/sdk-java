package com.marketdata.sdk.exception;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

/**
 * Root of the SDK exception hierarchy.
 *
 * <p>Sealed (ADR-002) so consumer {@code switch} statements over its subtypes are compile-time
 * exhaustive. Every instance carries the support context fields required by SDK requirements §6.2
 * and exposes a {@link #getSupportInfo()} string per §6.3.
 *
 * <p>Subtypes use {@link ErrorContext#empty()} for client-side validation errors that occur before
 * any HTTP request is dispatched.
 */
public abstract sealed class MarketDataException extends RuntimeException
    permits AuthenticationError,
        BadRequestError,
        NotFoundError,
        RateLimitError,
        ServerError,
        NetworkError,
        ParseError {

  private static final ZoneId EASTERN = ZoneId.of("America/New_York");
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final @Nullable String requestId;
  private final @Nullable String requestUrl;
  private final @Nullable Integer statusCode;
  private final ZonedDateTime timestamp;

  protected MarketDataException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, cause);
    this.requestId = context.requestId();
    this.requestUrl = context.requestUrl();
    this.statusCode = context.statusCode();
    this.timestamp = ZonedDateTime.now(EASTERN);
  }

  public @Nullable String getRequestId() {
    return requestId;
  }

  public @Nullable String getRequestUrl() {
    return requestUrl;
  }

  public @Nullable Integer getStatusCode() {
    return statusCode;
  }

  public ZonedDateTime getTimestamp() {
    return timestamp;
  }

  public String getExceptionType() {
    return getClass().getSimpleName();
  }

  /**
   * Multi-line, human-readable summary of the error and its context, intended to be copy-pasted
   * into a support ticket. Never contains the API token or request body.
   */
  public String getSupportInfo() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("Market Data SDK Error\n");
    sb.append("---------------------\n");
    sb.append("Type:        ").append(getExceptionType()).append('\n');
    sb.append("Message:     ").append(getMessage()).append('\n');
    sb.append("Status code: ").append(statusCode != null ? statusCode : "(n/a)").append('\n');
    sb.append("Request ID:  ").append(requestId != null ? requestId : "(n/a)").append('\n');
    sb.append("Request URL: ").append(requestUrl != null ? requestUrl : "(n/a)").append('\n');
    sb.append("Timestamp:   ").append(timestamp.format(TIMESTAMP_FORMAT)).append(" (US/Eastern)");
    return sb.toString();
  }
}
