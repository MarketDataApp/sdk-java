package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatusRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code markets} — reached through {@code client.markets().asCsv()}. Every endpoint
 * here returns a {@link CsvResponse} (opaque CSV text).
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class MarketsCsvResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  MarketsCsvResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  // ---------- universal + output-shaping params ----------

  public MarketsCsvResource dateFormat(DateFormat v) {
    return new MarketsCsvResource(transport, config.withDateFormat(v));
  }

  public MarketsCsvResource mode(Mode v) {
    return new MarketsCsvResource(transport, config.withMode(v));
  }

  public MarketsCsvResource limit(int v) {
    return new MarketsCsvResource(transport, config.withLimit(v));
  }

  public MarketsCsvResource offset(int v) {
    return new MarketsCsvResource(transport, config.withOffset(v));
  }

  public MarketsCsvResource columns(String... v) {
    return new MarketsCsvResource(transport, config.withColumns(java.util.List.of(v)));
  }

  public MarketsCsvResource human(boolean v) {
    return new MarketsCsvResource(transport, config.withHuman(v));
  }

  public MarketsCsvResource headers(boolean v) {
    return new MarketsCsvResource(transport, config.withHeaders(v));
  }

  // ---------- endpoints ----------

  public CompletableFuture<CsvResponse> statusAsync(MarketStatusRequest request) {
    return executeCsv(MarketsResource.statusSpec(request));
  }

  public CsvResponse status(MarketStatusRequest request) {
    return transport.joinSync(statusAsync(request));
  }

  // ---------- execute ----------

  private CompletableFuture<CsvResponse> executeCsv(RequestSpec.Builder b) {
    config.applyTo(b);
    b.format(Format.CSV);
    RequestSpec spec = b.build();
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                new CsvResponse(
                    new String(env.body(), StandardCharsets.UTF_8), env, spec.format()));
  }
}
