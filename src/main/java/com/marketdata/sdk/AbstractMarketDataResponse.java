package com.marketdata.sdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Shared implementation of {@link MarketDataResponse}: holds the flat payload plus the response
 * metadata (raw body, format, status, request id, url) and implements every accessor. Named
 * per-endpoint response types extend this with nothing more than their payload type binding and a
 * package-private constructor, so consumers cannot instantiate them (ADR-007).
 *
 * <p>Lives in the root package so the resource façades (also root) can construct the subtypes via
 * their package-private constructors, mirroring how the resource façades construct their responses.
 *
 * @param <T> the flat payload type.
 */
abstract class AbstractMarketDataResponse<T> implements MarketDataResponse<T> {

  private final T values;
  private final byte[] rawBody;
  private final Format format;
  private final int statusCode;
  private final @Nullable String requestId;
  private final URI requestUrl;

  AbstractMarketDataResponse(T values, HttpResponseEnvelope envelope, Format format) {
    this.values = Objects.requireNonNull(values, "values");
    this.rawBody = Objects.requireNonNull(envelope, "envelope").body().clone();
    this.format = Objects.requireNonNull(format, "format");
    this.statusCode = envelope.statusCode();
    this.requestId = envelope.requestId();
    this.requestUrl = envelope.url();
  }

  @Override
  public T values() {
    return values;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public boolean isNoData() {
    return statusCode == 404;
  }

  @Override
  public @Nullable String requestId() {
    return requestId;
  }

  @Override
  public URI requestUrl() {
    return requestUrl;
  }

  @Override
  public String json() {
    return new String(rawBody, StandardCharsets.UTF_8);
  }

  @Override
  public boolean isJson() {
    return format == Format.JSON;
  }

  @Override
  public boolean isCsv() {
    return format == Format.CSV;
  }

  @Override
  public boolean isHtml() {
    return format == Format.HTML;
  }

  @Override
  public void saveToFile(Path path) {
    try {
      Files.write(path, rawBody);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write response body to " + path, e);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "[status="
        + statusCode
        + ", format="
        + format.name().toLowerCase(Locale.ROOT)
        + ", bytes="
        + rawBody.length
        + ", url="
        + HttpDispatcher.safeUri(requestUrl)
        + "]";
  }
}
