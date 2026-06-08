package com.marketdata.sdk;

import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.OptionsStrikesRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CSV facet of {@code options} — reached through {@code client.options().asCsv()}. Every endpoint
 * here returns a {@link CsvResponse} (opaque CSV text). {@code lookup} is intentionally absent: it
 * is a scalar with no CSV representation (resource-architecture §2.4).
 *
 * <p>Carries the universal-param config from the typed resource and additionally exposes the
 * output-shaping {@code columns}/{@code human}/{@code headers} params, which only cohere with CSV.
 */
public final class OptionsCsvResource {

  private final HttpTransport transport;
  private final RequestConfig config;

  OptionsCsvResource(HttpTransport transport, RequestConfig config) {
    this.transport = transport;
    this.config = config;
  }

  // ---------- universal + output-shaping params ----------

  public OptionsCsvResource dateFormat(DateFormat v) {
    return new OptionsCsvResource(transport, config.withDateFormat(v));
  }

  public OptionsCsvResource mode(Mode v) {
    return new OptionsCsvResource(transport, config.withMode(v));
  }

  public OptionsCsvResource limit(int v) {
    return new OptionsCsvResource(transport, config.withLimit(v));
  }

  public OptionsCsvResource offset(int v) {
    return new OptionsCsvResource(transport, config.withOffset(v));
  }

  public OptionsCsvResource columns(String... v) {
    return new OptionsCsvResource(transport, config.withColumns(List.of(v)));
  }

  public OptionsCsvResource human(boolean v) {
    return new OptionsCsvResource(transport, config.withHuman(v));
  }

  public OptionsCsvResource headers(boolean v) {
    return new OptionsCsvResource(transport, config.withHeaders(v));
  }

  // ---------- endpoints ----------

  public CompletableFuture<CsvResponse> chainAsync(OptionsChainRequest request) {
    return executeCsv(OptionsResource.chainSpec(request));
  }

  public CsvResponse chain(OptionsChainRequest request) {
    return transport.joinSync(chainAsync(request));
  }

  public CompletableFuture<CsvResponse> quoteAsync(OptionsQuoteRequest request) {
    return executeCsv(
        OptionsResource.quoteSpec(
            request.optionSymbol(),
            request.date(),
            request.from(),
            request.to(),
            request.countback()));
  }

  public CsvResponse quote(OptionsQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  /** Fan-out: one CSV per symbol, mirroring the typed map (resource-architecture §2.5). */
  public CompletableFuture<Map<String, CsvResponse>> quotesAsync(OptionsQuotesRequest request) {
    List<String> symbols = request.optionSymbols();
    List<CompletableFuture<Map.Entry<String, CsvResponse>>> futures =
        new ArrayList<>(symbols.size());
    for (String symbol : symbols) {
      futures.add(
          executeCsv(
                  OptionsResource.quoteSpec(
                      symbol, request.date(), request.from(), request.to(), request.countback()))
              .thenApply(resp -> Map.entry(symbol, resp)));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            unused -> {
              Map<String, CsvResponse> result = new LinkedHashMap<>();
              for (CompletableFuture<Map.Entry<String, CsvResponse>> f : futures) {
                Map.Entry<String, CsvResponse> entry = f.join();
                result.put(entry.getKey(), entry.getValue());
              }
              return result;
            });
  }

  public Map<String, CsvResponse> quotes(OptionsQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  public CompletableFuture<CsvResponse> strikesAsync(OptionsStrikesRequest request) {
    return executeCsv(OptionsResource.strikesSpec(request));
  }

  public CsvResponse strikes(OptionsStrikesRequest request) {
    return transport.joinSync(strikesAsync(request));
  }

  public CompletableFuture<CsvResponse> expirationsAsync(OptionsExpirationsRequest request) {
    return executeCsv(OptionsResource.expirationsSpec(request));
  }

  public CsvResponse expirations(OptionsExpirationsRequest request) {
    return transport.joinSync(expirationsAsync(request));
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
