package com.marketdata.sdk.exception;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

public abstract sealed class MarketDataException extends RuntimeException
    permits AuthenticationError,
        BadRequestError,
        NotFoundError,
        RateLimitError,
        ServerError,
        NetworkError,
        ParseError {

  private static final long serialVersionUID = 1L;

  private final ErrorContext context;

  protected MarketDataException(String message, ErrorContext context, @Nullable Throwable cause) {
    super(message, cause);
    this.context = context;
  }

  public ErrorContext getContext() {
    return context;
  }

  public @Nullable String getRequestId() {
    return context.requestId();
  }

  public String getRequestUrl() {
    return context.requestUrl();
  }

  public int getStatusCode() {
    return context.statusCode();
  }

  public Instant getTimestamp() {
    return context.timestamp();
  }

  public String getExceptionType() {
    return getClass().getSimpleName();
  }

  public String getSupportInfo() {
    String requestId = getRequestId();
    String message = getMessage();
    return String.join(
        System.lineSeparator(),
        "--- MARKET DATA SUPPORT INFO ---",
        formatField("request_id:", requestId == null ? "(not provided)" : requestId),
        formatField("request_url:", getRequestUrl()),
        formatField("status_code:", String.valueOf(getStatusCode())),
        formatField("timestamp:", EASTERN_FORMATTER.format(getTimestamp())),
        formatField("message:", message == null ? "" : message),
        formatField("exception_type:", getExceptionType()),
        "--------------------------------");
  }

  private static final DateTimeFormatter EASTERN_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("America/New_York"));

  private static String formatField(String name, String value) {
    return String.format("%-16s%s", name, value);
  }
}
