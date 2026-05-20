package com.marketdata.sdk.exception;

import java.net.URI;
import java.net.URISyntaxException;
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

  /**
   * The request URL with any query string redacted (replaced by {@code ?…}). Mirrors the SDK's
   * ambient-log policy — query strings can carry PII (account IDs), competitive signal (queried
   * symbols), or hypothetical future credentials, none of which should land in consumer logs just
   * because someone called {@code logger.error("Request failed: " + ex.getRequestUrl())}. The full
   * URI (with query) is preserved internally; use {@link #getContext()} when raw access is
   * genuinely needed for diagnostics that won't be persisted.
   */
  public String getRequestUrl() {
    return redactQuery(context.requestUrl());
  }

  private static String redactQuery(String rawUrl) {
    try {
      URI uri = new URI(rawUrl);
      if (uri.getRawQuery() == null) {
        return rawUrl;
      }
      int qIndex = rawUrl.indexOf('?');
      return qIndex < 0 ? rawUrl : rawUrl.substring(0, qIndex) + "?…";
    } catch (URISyntaxException e) {
      // Defensive: never throw from a getter. If the stored URL is malformed, return verbatim —
      // it's the consumer's problem to diagnose, but not one to compound by hiding everything.
      return rawUrl;
    }
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
