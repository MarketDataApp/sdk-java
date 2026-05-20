package com.marketdata.sdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Carrier for an API response: typed model + raw body + metadata. Per SDK requirements §13.5,
 * exposes format-detection accessors ({@link #isJson()}, {@link #isCsv()}), no-data detection
 * ({@link #isNoData()}, matching the API's 404-with-{@code "s":"no_data"} envelope convention), and
 * {@link #saveToFile(Path)} for writing the raw body verbatim.
 *
 * <p>The {@link Format} enum is intentionally not exposed publicly (it has private values like
 * {@code HTML} that consumers shouldn't depend on). Consumers query format via the boolean
 * accessors.
 *
 * <p>Immutable. {@link #rawBody()} returns a defensive copy on every call.
 *
 * @param <T> the typed deserialization of {@link #rawBody()}.
 */
public final class Response<T> {

  private final T data;
  private final byte[] rawBody;
  private final Format format;
  private final int statusCode;
  private final @Nullable String requestId;
  private final URI requestUrl;

  private Response(
      T data,
      byte[] rawBody,
      Format format,
      int statusCode,
      @Nullable String requestId,
      URI requestUrl) {
    this.data = Objects.requireNonNull(data, "data");
    this.rawBody = Objects.requireNonNull(rawBody, "rawBody").clone();
    this.format = Objects.requireNonNull(format, "format");
    this.statusCode = statusCode;
    this.requestId = requestId;
    this.requestUrl = Objects.requireNonNull(requestUrl, "requestUrl");
  }

  /**
   * Package-private factory used by resource façades. Resources parse the envelope's body to a
   * typed {@code T}, then wrap.
   */
  static <T> Response<T> wrap(T data, HttpResponseEnvelope envelope, Format format) {
    return new Response<>(
        data, envelope.body(), format, envelope.statusCode(), envelope.requestId(), envelope.url());
  }

  /** The typed deserialization. Never {@code null}. */
  public T data() {
    return data;
  }

  /**
   * Defensive copy of the raw response bytes. Mutating the result does not affect this response.
   */
  public byte[] rawBody() {
    return rawBody.clone();
  }

  /** HTTP status code (currently one of 200, 203, 404). */
  public int statusCode() {
    return statusCode;
  }

  /** Absolute URL the response came from. */
  public URI requestUrl() {
    return requestUrl;
  }

  /**
   * Server-provided request id (Cloudflare {@code cf-ray}), or {@code null} when the response did
   * not carry one — useful when correlating with the support team. Matches the nullability shape of
   * {@link com.marketdata.sdk.exception.MarketDataException#getRequestId()} so consumers can branch
   * the same way regardless of which surface carries the id.
   */
  public @Nullable String requestId() {
    return requestId;
  }

  public boolean isJson() {
    return format == Format.JSON;
  }

  public boolean isCsv() {
    return format == Format.CSV;
  }

  /**
   * Whether the API signalled {@code {"s":"no_data"}} for this response. The backend uses HTTP 404
   * for that envelope (it is a successful "we have nothing for that query", not an error), so we
   * gate on the status code rather than parsing the body.
   */
  public boolean isNoData() {
    return statusCode == 404;
  }

  /**
   * Write the raw body verbatim to {@code path}, creating or overwriting it. The on-disk content
   * matches what the server sent — if you requested {@code ?format=csv}, you get CSV. Errors are
   * rewrapped as {@link UncheckedIOException} so {@code saveToFile} fits naturally inside a fluent
   * call chain.
   */
  public void saveToFile(Path path) {
    try {
      Files.write(path, rawBody);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write response body to " + path, e);
    }
  }

  /**
   * Log-safe representation: status, format, byte count, and the request URL with the query string
   * redacted (§16 — token, account_id, symbol queries must not persist through {@code toString}).
   * {@code data} is intentionally omitted: consumers that need the payload have {@link #data()};
   * embedding it here would let a routine {@code log.info(response)} leak a {@code RequestHeaders}
   * map (Authorization, client IP) or whatever else the future resource models carry.
   */
  @Override
  public String toString() {
    return "Response[status="
        + statusCode
        + ", format="
        + format.name().toLowerCase(java.util.Locale.ROOT)
        + ", bytes="
        + rawBody.length
        + ", url="
        + HttpDispatcher.safeUri(requestUrl)
        + "]";
  }
}
