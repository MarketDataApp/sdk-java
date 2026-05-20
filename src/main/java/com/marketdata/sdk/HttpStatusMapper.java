package com.marketdata.sdk;

import com.marketdata.sdk.exception.AuthenticationError;
import com.marketdata.sdk.exception.BadRequestError;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.exception.NotFoundError;
import com.marketdata.sdk.exception.RateLimitError;
import com.marketdata.sdk.exception.ServerError;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

final class HttpStatusMapper {

  static @Nullable MarketDataException map(int statusCode, ErrorContext context) {
    return map(statusCode, context, null);
  }

  /**
   * Maps an HTTP status to its typed exception. When {@code retryAfter} is non-null, it is attached
   * to the resulting {@link ServerError} so the retry policy can honor §9.4. The other subtypes
   * ignore it — only server errors retry, and only retries care about Retry-After.
   *
   * <p>Unmapped status codes are split by range rather than lumped into a single bucket:
   *
   * <ul>
   *   <li><strong>5xx</strong> → {@link ServerError} (retryable).
   *   <li><strong>4xx</strong> (other than 401/404/429) → {@link BadRequestError} with the status
   *       code in the message — the request itself was malformed for some endpoint-specific reason
   *       (403 forbidden, 422 unprocessable entity, etc.).
   *   <li><strong>3xx</strong> → {@link BadRequestError} with a "redirect" message. The transport's
   *       {@code HttpClient} follows redirects per {@code NORMAL} policy, so a 3xx escaping that
   *       means the redirect could not be followed (e.g., cross-protocol, max redirects). Surfaces
   *       as a non-retryable error — retrying would just hit the same redirect.
   *   <li><strong>1xx</strong> → {@link BadRequestError} defensively. {@code HttpClient} handles
   *       {@code 100 Continue} itself, so reaching here is a server-protocol oddity.
   *   <li>Anything else (negative, &gt; 599, etc.) → {@link BadRequestError} with the raw status.
   * </ul>
   */
  static @Nullable MarketDataException map(
      int statusCode, ErrorContext context, @Nullable Duration retryAfter) {
    if (statusCode >= 200 && statusCode < 300) {
      return null;
    }
    return switch (statusCode) {
      case 400 -> new BadRequestError("Bad request", context);
      case 401 -> new AuthenticationError("Authentication failed", context);
      case 404 -> new NotFoundError("Not found", context);
      case 429 -> new RateLimitError("Rate limit exceeded", context);
      default -> mapByRange(statusCode, context, retryAfter);
    };
  }

  private static MarketDataException mapByRange(
      int statusCode, ErrorContext context, @Nullable Duration retryAfter) {
    if (statusCode >= 500 && statusCode <= 599) {
      return new ServerError("Server error: " + statusCode, context, null, retryAfter);
    }
    if (statusCode >= 400 && statusCode <= 499) {
      return new BadRequestError("Client error: HTTP " + statusCode, context);
    }
    if (statusCode >= 300 && statusCode <= 399) {
      // followRedirects(NORMAL) drains the standard cases; a 3xx surviving here means the
      // redirect could not be followed (cross-protocol, max-redirects hit, etc.). Retrying
      // would hit the same redirect, so route through the non-retryable BadRequestError
      // bucket with a message that points at the likely culprit.
      return new BadRequestError(
          "Unhandled redirect: HTTP "
              + statusCode
              + " — the SDK follows standard redirects; this response was not followed."
              + " Check baseUrl or proxy configuration.",
          context);
    }
    if (statusCode >= 100 && statusCode <= 199) {
      return new BadRequestError("Unexpected informational response: HTTP " + statusCode, context);
    }
    return new BadRequestError("Unexpected HTTP status: " + statusCode, context);
  }

  private HttpStatusMapper() {}
}
