package com.marketdata.sdk;

import com.marketdata.sdk.funds.FundCandlesRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code funds} — reached through {@code client.funds().asCsv()}. Every endpoint here
 * returns a {@link CsvResponse} (opaque CSV text). No candle auto-chunking on this facet either:
 * funds serve no intraday resolutions, so the §12 year-span split never applies.
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class FundsCsvResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  FundsCsvResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  // ---------- universal + output-shaping params ----------

  public FundsCsvResource dateFormat(DateFormat v) {
    return new FundsCsvResource(transport, config.withDateFormat(v));
  }

  public FundsCsvResource mode(Mode v) {
    return new FundsCsvResource(transport, config.withMode(v));
  }

  public FundsCsvResource limit(int v) {
    return new FundsCsvResource(transport, config.withLimit(v));
  }

  public FundsCsvResource offset(int v) {
    return new FundsCsvResource(transport, config.withOffset(v));
  }

  public FundsCsvResource columns(String... v) {
    return new FundsCsvResource(transport, config.withColumns(java.util.List.of(v)));
  }

  public FundsCsvResource human(boolean v) {
    return new FundsCsvResource(transport, config.withHuman(v));
  }

  public FundsCsvResource headers(boolean v) {
    return new FundsCsvResource(transport, config.withHeaders(v));
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
