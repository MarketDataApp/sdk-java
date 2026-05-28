package com.marketdata.sdk;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.options.OptionsExpirations;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookup;
import com.marketdata.sdk.options.OptionsLookupRequest;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Options endpoints documented at {@code https://api.marketdata.app/docs/api/options/}. All five
 * endpoints — {@code lookup}, {@code expirations}, {@code strikes}, {@code quotes}, and {@code
 * chain} — are versioned ({@code /v1/options/...}).
 *
 * <p>Constructed once per {@link MarketDataClient}; consumers reach it through {@code
 * client.options()}. Constructor is package-private (ADR-007) — consumers cannot instantiate.
 *
 * <p>Every endpoint returns a {@link Response} carrying both the typed model and the raw body so
 * consumers can access §13.5 response features ({@code isCsv()}, {@code saveToFile()}, …) without
 * the resource caring about format choice.
 */
public final class OptionsResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;

  OptionsResource(HttpTransport transport, JsonResponseParser parser) {
    this.transport = transport;
    this.parser = parser;
    parser.registerModule(wireFormatModule());
  }

  /**
   * Build the Jackson module that maps this resource's response records to their custom
   * deserializers. Each call returns a fresh {@link SimpleModule}; tests that need the same wiring
   * without constructing a full resource can register this directly on a bare parser.
   */
  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-options");
    m.addDeserializer(OptionsLookup.class, new OptionsLookupDeserializer());
    m.addDeserializer(OptionsExpirations.class, new OptionsExpirationsDeserializer());
    return m;
  }

  /**
   * Async: convert a human-readable option description ({@code "AAPL 7/26/23 $200 Call"}) into a
   * well-formed OCC symbol ({@code "AAPL230726C00200000"}). The request's {@code userInput} is
   * URL-encoded per-segment so spaces, {@code $}, and other reserved characters travel safely
   * without losing the natural {@code /} separators in dates like {@code 7/26/23}.
   */
  public CompletableFuture<Response<OptionsLookup>> lookupAsync(OptionsLookupRequest request) {
    RequestSpec spec =
        RequestSpec.get("options/lookup/" + PathSegments.encode(request.userInput())).build();
    return executeAndWrap(spec, OptionsLookup.class);
  }

  /** Sync wrapper for {@link #lookupAsync(OptionsLookupRequest)}. */
  public Response<OptionsLookup> lookup(OptionsLookupRequest request) {
    return transport.joinSync(lookupAsync(request));
  }

  /**
   * Async: fetch the available option-expiration dates for the request's underlying. The optional
   * {@code strike} and {@code date} filters narrow the result.
   *
   * <p>The §3 universal {@code dateformat} parameter is left to the API's default ({@code
   * timestamp}); the typed {@link OptionsExpirations#data} surface decodes whichever format the API
   * returns via {@link MarketDataDates#parseDateField}. Consumers that want a specific wire format
   * for {@link Response#rawBody()} / {@link Response#saveToFile} access pass it explicitly once the
   * universal-parameters overload lands (follow-up).
   */
  public CompletableFuture<Response<OptionsExpirations>> expirationsAsync(
      OptionsExpirationsRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/expirations/" + PathSegments.encode(request.symbol()));
    if (request.strike() != null) {
      b.query("strike", request.strike());
    }
    if (request.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(request.date()));
    }
    return executeAndWrap(b.build(), OptionsExpirations.class);
  }

  /** Sync wrapper for {@link #expirationsAsync(OptionsExpirationsRequest)}. */
  public Response<OptionsExpirations> expirations(OptionsExpirationsRequest request) {
    return transport.joinSync(expirationsAsync(request));
  }

  // ---------- internal helpers ----------

  private <T> CompletableFuture<Response<T>> executeAndWrap(RequestSpec spec, Class<T> type) {
    return transport
        .executeAsync(spec)
        .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
  }
}
