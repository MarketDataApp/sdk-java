package com.marketdata.sdk;

import com.marketdata.sdk.funds.FundCandlesRequest;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code funds} — reached through {@code client.funds().asCsv()}. Every endpoint here
 * returns a {@link CsvResponse} (opaque CSV text). No candle auto-chunking on this facet either:
 * funds serve no intraday resolutions, so the §12 year-span split never applies.
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class FundsCsvResource extends FormattedResource<FundsCsvResource> {

  FundsCsvResource(HttpTransport transport, RequestConfig config) {
    super(transport, config);
  }

  // ---------- universal + output-shaping params: inherited from FormattedResource ----------

  @Override
  FundsCsvResource withConfig(RequestConfig config) {
    return new FundsCsvResource(transport, config);
  }

  // ---------- endpoints ----------

  public CompletableFuture<CsvResponse> candlesAsync(FundCandlesRequest request) {
    return executeCsv(FundsResource.candlesSpec(request));
  }

  public CsvResponse candles(FundCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  // ---------- execute ----------

  private CompletableFuture<CsvResponse> executeCsv(RequestSpec.Builder b) {
    return TextResponses.execute(transport, config, b, Format.CSV, CsvResponse::new);
  }
}
