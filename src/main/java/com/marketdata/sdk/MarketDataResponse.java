package com.marketdata.sdk;

import java.net.URI;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Uniform surface every endpoint response implements. {@code T} is the <em>flat</em> payload the
 * endpoint produces — a {@code List<Row>} for tabular endpoints, a {@code String} for the scalar
 * {@code options.lookup}, and so on — reached through the single {@link #values()} accessor.
 *
 * <p>The point is that a consumer learns one shape and reuses it across every resource: {@code
 * values()} for the data (typed per endpoint), and the same metadata accessors everywhere. Concrete
 * responses are named per endpoint (e.g. {@code OptionsChainResponse implements
 * MarketDataResponse<List<OptionQuote>>}) so signatures read well and the compiler enforces the
 * accessor.
 *
 * @param <T> the flat payload type for this endpoint.
 */
public interface MarketDataResponse<T> {

  /** The typed payload — {@code List<...>}, a scalar, etc. Never {@code null}. */
  T values();

  /** HTTP status code (one of 200, 203, 404). */
  int statusCode();

  /**
   * Whether the API signalled {@code {"s":"no_data"}} (HTTP 404 with that envelope) — a successful
   * "nothing for that query", distinct from an error.
   */
  boolean isNoData();

  /** Server-provided request id (Cloudflare {@code cf-ray}), or {@code null} when absent. */
  @Nullable String requestId();

  /** Absolute URL the response came from. */
  URI requestUrl();

  /** The raw response body as text (the original payload the API returned). */
  String json();

  boolean isJson();

  boolean isCsv();

  /**
   * Whether the body is HTML — typically a misrouted request that hit the web tier (marketing/error
   * page) rather than the API tier.
   */
  boolean isHtml();

  /** Write the raw body verbatim to {@code path}, creating or overwriting it. */
  void saveToFile(Path path);
}
