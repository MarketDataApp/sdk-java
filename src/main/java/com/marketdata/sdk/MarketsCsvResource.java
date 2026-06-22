package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatusRequest;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code markets} — reached through {@code client.markets().asCsv()}. Every endpoint
 * here returns a {@link CsvResponse} (opaque CSV text).
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class MarketsCsvResource extends FormattedResource<MarketsCsvResource> {

  MarketsCsvResource(HttpTransport transport, RequestConfig config) {
    super(transport, config);
  }

  // ---------- universal + output-shaping params: inherited from FormattedResource ----------

  @Override
  MarketsCsvResource withConfig(RequestConfig config) {
    return new MarketsCsvResource(transport, config);
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
    return TextResponses.execute(transport, config, b, Format.CSV, CsvResponse::new);
  }
}
