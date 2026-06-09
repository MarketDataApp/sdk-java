package com.marketdata.sdk.options;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Response shape for {@code GET /v1/options/strikes/{underlying}/} — the strike prices available
 * for each expiration in the underlying's option chain.
 *
 * <p>The wire-format is unique in the API: there is no parallel-arrays envelope, and the response
 * carries one top-level key per expiration date (e.g. {@code "2025-01-17"} → {@code [140, 145,
 * 150]}) plus the universal {@code "s"} and {@code "updated"} metadata. The SDK lifts those into a
 * positional list of {@link ExpirationStrikes} rows so consumers iterate naturally; ordering
 * follows what the API returns (the backend sorts expirations ascending).
 *
 * @param expirations one entry per expiration in chronological order; never {@code null}. Empty
 *     when the {@code "s":"no_data"} envelope is received.
 * @param updated when the server last refreshed this strikes table, in {@code America/New_York}.
 *     {@code null} only when the no-data envelope omits the {@code updated} field.
 */
public record OptionsStrikes(
    List<ExpirationStrikes> expirations, @org.jspecify.annotations.Nullable ZonedDateTime updated) {

  public OptionsStrikes {
    Objects.requireNonNull(expirations, "expirations");
    expirations = List.copyOf(expirations);
  }
}
