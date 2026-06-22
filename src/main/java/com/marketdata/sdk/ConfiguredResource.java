package com.marketdata.sdk;

import java.util.List;

/**
 * Package-private self-typed base for resources that carry the §3 universal query parameters as an
 * immutable {@link RequestConfig}. Each setter returns a configured copy of the <em>concrete</em>
 * resource type (via the {@code SELF} self-type and {@link #withConfig}), so endpoint chaining
 * stays on the concrete class — {@code client.stocks().mode(LIVE).candles(req)} still reaches
 * {@code candles}, which is declared on {@code StocksResource}, not here.
 *
 * <p>The five params declared here ({@code dateFormat}, {@code mode}, {@code limit}, {@code
 * offset}, {@code columns}) are valid on both the typed path and the CSV facet. The CSV-only {@code
 * human}/{@code headers} live on {@link FormattedResource}.
 *
 * <p>{@link #withConfig} is package-private so the internal {@link RequestConfig} never leaks onto
 * the public API (ADR-007); the class itself is package-private for the same reason. The inherited
 * setters remain public on the {@code public final} concrete subtypes, so Java and Kotlin callers
 * invoke them directly on the concrete type.
 */
abstract class ConfiguredResource<SELF extends ConfiguredResource<SELF>> {

  final HttpTransport transport;
  final RequestConfig config;

  ConfiguredResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  /** Returns a copy of the concrete resource carrying {@code config}. */
  abstract SELF withConfig(RequestConfig config);

  /** Returns a copy that requests {@code dateformat} on every subsequent call. */
  public SELF dateFormat(DateFormat dateFormat) {
    return withConfig(config.withDateFormat(dateFormat));
  }

  /**
   * Returns a copy with the data-freshness {@code mode} (cached honored only by quote endpoints).
   */
  public SELF mode(Mode mode) {
    return withConfig(config.withMode(mode));
  }

  /** Returns a copy with the pagination {@code limit}. */
  public SELF limit(int limit) {
    return withConfig(config.withLimit(limit));
  }

  /** Returns a copy with the pagination {@code offset}. */
  public SELF offset(int offset) {
    return withConfig(config.withOffset(offset));
  }

  /**
   * Returns a copy that projects the response to the given columns (wire field names). Fields not
   * requested decode to {@code null}; a requested column the API fails to return surfaces as a
   * {@link com.marketdata.sdk.exception.ParseError} rather than a silent null.
   */
  public SELF columns(String... columns) {
    return withConfig(config.withColumns(List.of(columns)));
  }
}
