package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionsChain;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.OptionsExpirations;
import com.marketdata.sdk.options.OptionsExpirationsRequest;
import com.marketdata.sdk.options.OptionsLookup;
import com.marketdata.sdk.options.OptionsLookupRequest;
import com.marketdata.sdk.options.OptionsQuoteRequest;
import com.marketdata.sdk.options.OptionsQuotes;
import com.marketdata.sdk.options.OptionsQuotesRequest;
import com.marketdata.sdk.options.OptionsStrikes;
import com.marketdata.sdk.options.OptionsStrikesRequest;
import com.marketdata.sdk.options.StrikeFilter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Options endpoints ({@code /v1/options/...}). Reached through {@code client.options()}.
 *
 * <p>The resource is an <em>immutable configured value</em> (ADR / resource-architecture §1.3): the
 * universal-parameter setters ({@link #dateFormat}, {@link #mode}, {@link #limit}, {@link #offset},
 * {@link #columns}) each return a configured copy, so "configure once, call many" works and the
 * config carries into the {@link #asCsv()} facet. Every endpoint returns a named {@link
 * MarketDataResponse} whose {@link MarketDataResponse#values()} is the flat payload.
 *
 * <p>Constructor is package-private (ADR-007) — consumers cannot instantiate.
 */
public final class OptionsResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;
  private final RequestConfig config;

  /** Client-facing constructor: registers the wire-format module once, starts with empty config. */
  OptionsResource(HttpTransport transport, JsonResponseParser parser) {
    this(transport, parser, RequestConfig.empty());
    parser.registerModule(wireFormatModule());
  }

  private OptionsResource(
      HttpTransport transport, JsonResponseParser parser, RequestConfig config) {
    this.transport = transport;
    this.parser = parser;
    this.config = config;
  }

  // ---------- universal parameters (type-preserving + columns) ----------

  /** Returns a copy that requests {@code dateformat} on every subsequent call. */
  public OptionsResource dateFormat(DateFormat dateFormat) {
    return new OptionsResource(transport, parser, config.withDateFormat(dateFormat));
  }

  /**
   * Returns a copy with the data-freshness {@code mode} (cached honored only by quote endpoints).
   */
  public OptionsResource mode(Mode mode) {
    return new OptionsResource(transport, parser, config.withMode(mode));
  }

  /** Returns a copy with the pagination {@code limit}. */
  public OptionsResource limit(int limit) {
    return new OptionsResource(transport, parser, config.withLimit(limit));
  }

  /** Returns a copy with the pagination {@code offset}. */
  public OptionsResource offset(int offset) {
    return new OptionsResource(transport, parser, config.withOffset(offset));
  }

  /**
   * Returns a copy that projects the response to the given columns (wire field names). Fields not
   * requested decode to {@code null}; a requested column the API fails to return surfaces as a
   * {@link com.marketdata.sdk.exception.ParseError} rather than a silent null.
   */
  public OptionsResource columns(String... columns) {
    return new OptionsResource(transport, parser, config.withColumns(List.of(columns)));
  }

  // ---------- format facet ----------

  /** A CSV-flavored view of this resource (carrying the same universal-param config). */
  public OptionsCsvResource asCsv() {
    return new OptionsCsvResource(transport, config);
  }

  /**
   * HTML facet — built but not exposed to consumers (the backend returns no HTML for any data
   * endpoint today). Package-private so it can be exercised by tests; flip to {@code public} when
   * the server supports {@code format=html}.
   */
  OptionsHtmlResource asHtml() {
    return new OptionsHtmlResource(transport, config);
  }

  // ---------- endpoints (typed) ----------

  /** Async: resolve a human-readable option description into an OCC symbol. */
  public CompletableFuture<OptionsLookupResponse> lookupAsync(OptionsLookupRequest request) {
    // lookup carries no universal params (no GLOBAL_PARAMS on the backend).
    RequestSpec spec = lookupSpec(request).build();
    return execute(
        spec,
        OptionsLookup.class,
        (d, env, fmt) -> new OptionsLookupResponse(d.optionSymbol(), env, fmt));
  }

  /** Sync wrapper for {@link #lookupAsync(OptionsLookupRequest)}. */
  public OptionsLookupResponse lookup(OptionsLookupRequest request) {
    return transport.joinSync(lookupAsync(request));
  }

  /** Async: fetch the available option-expiration dates for the request's underlying. */
  public CompletableFuture<OptionsExpirationsResponse> expirationsAsync(
      OptionsExpirationsRequest request) {
    RequestSpec.Builder b = expirationsSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        OptionsExpirations.class,
        (d, env, fmt) -> new OptionsExpirationsResponse(d.expirations(), d.updated(), env, fmt));
  }

  /** Sync wrapper for {@link #expirationsAsync(OptionsExpirationsRequest)}. */
  public OptionsExpirationsResponse expirations(OptionsExpirationsRequest request) {
    return transport.joinSync(expirationsAsync(request));
  }

  /** Async: fetch the strike prices available for each expiration on the request's underlying. */
  public CompletableFuture<OptionsStrikesResponse> strikesAsync(OptionsStrikesRequest request) {
    RequestSpec.Builder b = strikesSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        OptionsStrikes.class,
        (d, env, fmt) -> new OptionsStrikesResponse(d.expirations(), d.updated(), env, fmt));
  }

  /** Sync wrapper for {@link #strikesAsync(OptionsStrikesRequest)}. */
  public OptionsStrikesResponse strikes(OptionsStrikesRequest request) {
    return transport.joinSync(strikesAsync(request));
  }

  /** Async: fetch the current (or historical) quote for a single OCC option symbol. */
  public CompletableFuture<OptionsQuotesResponse> quoteAsync(OptionsQuoteRequest request) {
    RequestSpec.Builder b =
        quoteSpec(
            request.optionSymbol(),
            request.date(),
            request.from(),
            request.to(),
            request.countback());
    config.applyTo(b);
    return execute(
        b.build(),
        OptionsQuotes.class,
        (d, env, fmt) -> new OptionsQuotesResponse(d.quotes(), env, fmt));
  }

  /** Sync wrapper for {@link #quoteAsync(OptionsQuoteRequest)}. */
  public OptionsQuotesResponse quote(OptionsQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  /**
   * Async: fetch quotes for multiple OCC option symbols concurrently (one request per symbol — the
   * backend path is single-symbol). Returns a per-symbol map (insertion order preserved); the
   * future completes exceptionally if any single request fails (fail-fast).
   */
  public CompletableFuture<Map<String, OptionsQuotesResponse>> quotesAsync(
      OptionsQuotesRequest request) {
    List<String> symbols = request.optionSymbols();
    List<CompletableFuture<Map.Entry<String, OptionsQuotesResponse>>> futures =
        new ArrayList<>(symbols.size());
    for (String symbol : symbols) {
      RequestSpec.Builder b =
          quoteSpec(symbol, request.date(), request.from(), request.to(), request.countback());
      config.applyTo(b);
      futures.add(
          execute(
                  b.build(),
                  OptionsQuotes.class,
                  (d, env, fmt) -> new OptionsQuotesResponse(d.quotes(), env, fmt))
              .thenApply(resp -> Map.entry(symbol, resp)));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            unused -> {
              Map<String, OptionsQuotesResponse> result = new LinkedHashMap<>();
              for (CompletableFuture<Map.Entry<String, OptionsQuotesResponse>> f : futures) {
                Map.Entry<String, OptionsQuotesResponse> entry = f.join();
                result.put(entry.getKey(), entry.getValue());
              }
              return result;
            });
  }

  /** Sync wrapper for {@link #quotesAsync(OptionsQuotesRequest)}. */
  public Map<String, OptionsQuotesResponse> quotes(OptionsQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  /** Async: fetch the full option chain for the request's underlying. */
  public CompletableFuture<OptionsChainResponse> chainAsync(OptionsChainRequest request) {
    RequestSpec.Builder b = chainSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        OptionsChain.class,
        (d, env, fmt) -> new OptionsChainResponse(d.chain(), env, fmt));
  }

  /** Sync wrapper for {@link #chainAsync(OptionsChainRequest)}. */
  public OptionsChainResponse chain(OptionsChainRequest request) {
    return transport.joinSync(chainAsync(request));
  }

  // ---------- execute ----------

  private <D, R> CompletableFuture<R> execute(
      RequestSpec spec, Class<D> decodeType, ResponseFactory<D, R> factory) {
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                factory.create(
                    parser.parse(env, decodeType, config.columns()), env, spec.format()));
  }

  @FunctionalInterface
  interface ResponseFactory<D, R> {
    R create(D decoded, HttpResponseEnvelope envelope, Format format);
  }

  // ---------- request spec builders (package-private static — reused by the facets) ----------

  static RequestSpec.Builder lookupSpec(OptionsLookupRequest request) {
    return RequestSpec.get("options/lookup/" + PathSegments.encode(request.userInput()));
  }

  static RequestSpec.Builder expirationsSpec(OptionsExpirationsRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/expirations/" + PathSegments.encode(request.symbol()));
    if (request.strike() != null) {
      b.query("strike", request.strike());
    }
    if (request.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(request.date()));
    }
    return b;
  }

  static RequestSpec.Builder strikesSpec(OptionsStrikesRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/strikes/" + PathSegments.encode(request.symbol()));
    if (request.expiration() != null) {
      b.query("expiration", DateTimeFormatter.ISO_LOCAL_DATE.format(request.expiration()));
    }
    if (request.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(request.date()));
    }
    return b;
  }

  static RequestSpec.Builder quoteSpec(
      String optionSymbol,
      @Nullable LocalDate date,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable Integer countback) {
    RequestSpec.Builder b = RequestSpec.get("options/quotes/" + PathSegments.encode(optionSymbol));
    if (date != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(date));
    }
    if (from != null) {
      b.query("from", DateTimeFormatter.ISO_LOCAL_DATE.format(from));
    }
    if (to != null) {
      b.query("to", DateTimeFormatter.ISO_LOCAL_DATE.format(to));
    }
    if (countback != null) {
      b.query("countback", countback);
    }
    return b;
  }

  static RequestSpec.Builder chainSpec(OptionsChainRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/chain/" + PathSegments.encode(request.symbol()));
    applyChainParams(b, request);
    return b;
  }

  private static void applyChainParams(RequestSpec.Builder b, OptionsChainRequest r) {
    if (r.expirationFilter() != null) {
      applyExpirationFilter(b, r.expirationFilter());
    }
    if (r.weekly() != null) {
      b.query("weekly", r.weekly());
    }
    if (r.monthly() != null) {
      b.query("monthly", r.monthly());
    }
    if (r.quarterly() != null) {
      b.query("quarterly", r.quarterly());
    }
    if (r.am() != null) {
      b.query("am", r.am());
    }
    if (r.pm() != null) {
      b.query("pm", r.pm());
    }
    if (r.nonstandard() != null) {
      b.query("nonstandard", r.nonstandard());
    }
    if (r.strikeFilter() != null) {
      b.query("strike", strikeFilterWireValue(r.strikeFilter()));
    }
    if (r.delta() != null) {
      b.query("delta", r.delta());
    }
    if (r.strikeLimit() != null) {
      b.query("strikeLimit", r.strikeLimit());
    }
    if (r.strikeRange() != null) {
      b.query("range", r.strikeRange().wireValue());
    }
    if (r.minBid() != null) {
      b.query("minBid", r.minBid());
    }
    if (r.maxBid() != null) {
      b.query("maxBid", r.maxBid());
    }
    if (r.minAsk() != null) {
      b.query("minAsk", r.minAsk());
    }
    if (r.maxAsk() != null) {
      b.query("maxAsk", r.maxAsk());
    }
    if (r.maxBidAskSpread() != null) {
      b.query("maxBidAskSpread", r.maxBidAskSpread());
    }
    if (r.maxBidAskSpreadPct() != null) {
      b.query("maxBidAskSpreadPct", r.maxBidAskSpreadPct());
    }
    if (r.minOpenInterest() != null) {
      b.query("minOpenInterest", r.minOpenInterest());
    }
    if (r.minVolume() != null) {
      b.query("minVolume", r.minVolume());
    }
    if (r.side() != null) {
      b.query("side", r.side().wireValue());
    }
    if (r.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(r.date()));
    }
  }

  private static void applyExpirationFilter(RequestSpec.Builder b, ExpirationFilter f) {
    if (f instanceof ExpirationFilter.OnDate v) {
      b.query("expiration", DateTimeFormatter.ISO_LOCAL_DATE.format(v.date()));
    } else if (f instanceof ExpirationFilter.Dte v) {
      b.query("dte", v.days());
    } else if (f instanceof ExpirationFilter.Between v) {
      b.query("from", DateTimeFormatter.ISO_LOCAL_DATE.format(v.from()));
      b.query("to", DateTimeFormatter.ISO_LOCAL_DATE.format(v.to()));
    } else if (f instanceof ExpirationFilter.MonthYear v) {
      b.query("month", v.month());
      b.query("year", v.year());
    } else if (f instanceof ExpirationFilter.All) {
      b.query("expiration", "all");
    } else {
      throw new IllegalStateException("unhandled ExpirationFilter variant: " + f);
    }
  }

  private static String strikeFilterWireValue(StrikeFilter f) {
    if (f instanceof StrikeFilter.Exact v) {
      return formatStrike(v.price());
    } else if (f instanceof StrikeFilter.Range v) {
      return formatStrike(v.min()) + "-" + formatStrike(v.max());
    } else if (f instanceof StrikeFilter.Comparison v) {
      return v.operator().wireValue() + formatStrike(v.price());
    }
    throw new IllegalStateException("unhandled StrikeFilter variant: " + f);
  }

  private static String formatStrike(double v) {
    if (v == Math.floor(v) && !Double.isInfinite(v)) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }

  // ---------- wire-format module ----------

  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-options");
    m.addDeserializer(OptionsLookup.class, new OptionsLookupDeserializer());
    m.addDeserializer(OptionsExpirations.class, new OptionsExpirationsDeserializer());
    m.addDeserializer(OptionsStrikes.class, new OptionsStrikesDeserializer());
    m.addDeserializer(OptionsQuotes.class, optionRowsDeserializer(OptionsQuotes::new));
    m.addDeserializer(OptionsChain.class, optionRowsDeserializer(OptionsChain::new));
    return m;
  }

  /**
   * Structural columns that must be present whenever they're requested. A {@code columns}
   * projection may legitimately drop any of these (→ {@code null}); but if the consumer asked for
   * one (or used no {@code columns} filter at all) and the API omitted it, that's an anomaly, not a
   * projection — Option A turns it into a {@link com.marketdata.sdk.exception.ParseError}. The
   * model-derived values ({@code iv} + the greeks) are excluded: they may legitimately be null even
   * when present.
   */
  private static final List<String> OPTION_REQUIRED_FIELDS =
      List.of(
          "optionSymbol",
          "underlying",
          "expiration",
          "side",
          "strike",
          "firstTraded",
          "dte",
          "updated",
          "bid",
          "bidSize",
          "mid",
          "ask",
          "askSize",
          "last",
          "openInterest",
          "volume",
          "inTheMoney",
          "intrinsicValue",
          "extrinsicValue",
          "underlyingPrice");

  /** Every column on the option row — all optional at the wire level (projection-friendly). */
  private static final List<String> OPTION_ALL_FIELDS =
      List.of(
          "optionSymbol",
          "underlying",
          "expiration",
          "side",
          "strike",
          "firstTraded",
          "dte",
          "updated",
          "bid",
          "bidSize",
          "mid",
          "ask",
          "askSize",
          "last",
          "openInterest",
          "volume",
          "inTheMoney",
          "intrinsicValue",
          "extrinsicValue",
          "underlyingPrice",
          "iv",
          "delta",
          "gamma",
          "theta",
          "vega",
          "rho");

  /**
   * Deserializer for the option-quote rows. Every column is optional at the wire level so a {@code
   * columns} projection decodes cleanly to nulls; the strict guarantee is restored by {@link
   * #validateRequestedColumns} (Option A), which fails loudly if a <em>requested</em> required
   * column was omitted.
   */
  private static <T> JsonDeserializer<T> optionRowsDeserializer(
      Function<List<OptionQuote>, T> wrapper) {
    return new JsonDeserializer<>() {
      @Override
      public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode root = p.readValueAsTree();
        List<OptionQuote> rows =
            ParallelArrays.zip(
                p, root, List.of(), OPTION_ALL_FIELDS, OptionsResource::buildOptionRow);
        validateRequestedColumns(p, root, rows, ctxt);
        return wrapper.apply(rows);
      }
    };
  }

  /** Builds one {@link OptionQuote} from a row, reading every column leniently (absent → null). */
  private static OptionQuote buildOptionRow(ParallelArrays.Row row) throws IOException {
    return new OptionQuote(
        row.textOrNull("optionSymbol"),
        row.textOrNull("underlying"),
        dateOrNull(row, "expiration"),
        row.textOrNull("side"),
        row.dblOrNull("strike"),
        dateOrNull(row, "firstTraded"),
        intOrNull(row.lngOrNull("dte")),
        dateOrNull(row, "updated"),
        row.dblOrNull("bid"),
        row.lngOrNull("bidSize"),
        row.dblOrNull("mid"),
        row.dblOrNull("ask"),
        row.lngOrNull("askSize"),
        row.dblOrNull("last"),
        row.lngOrNull("openInterest"),
        row.lngOrNull("volume"),
        row.boolOrNull("inTheMoney"),
        row.dblOrNull("intrinsicValue"),
        row.dblOrNull("extrinsicValue"),
        row.dblOrNull("underlyingPrice"),
        row.dblOrNull("iv"),
        row.dblOrNull("delta"),
        row.dblOrNull("gamma"),
        row.dblOrNull("theta"),
        row.dblOrNull("vega"),
        row.dblOrNull("rho"));
  }

  private static @Nullable ZonedDateTime dateOrNull(ParallelArrays.Row row, String field)
      throws IOException {
    JsonNode n = row.nodeOrNull(field);
    return n == null ? null : MarketDataDates.parseTimestampField(null, n, field);
  }

  private static @Nullable Integer intOrNull(@Nullable Long v) {
    return v == null ? null : v.intValue();
  }

  /**
   * Option A: for every required column that the consumer asked for (explicitly via {@code
   * columns}, or implicitly by not projecting at all), verify the API actually returned it. A
   * requested-but- absent required column throws, so the {@code null} a consumer sees only ever
   * means "I projected it away" — never "the backend silently dropped it".
   */
  private static void validateRequestedColumns(
      JsonParser p, JsonNode root, List<OptionQuote> rows, DeserializationContext ctxt)
      throws JsonMappingException {
    if (rows.isEmpty()) {
      return; // no_data / empty response — no projection to validate
    }
    Object attr = ctxt.getAttribute(JsonResponseParser.REQUESTED_COLUMNS_ATTR);
    List<String> requested =
        attr instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    for (String field : OPTION_REQUIRED_FIELDS) {
      boolean asked = requested.isEmpty() || requested.contains(field);
      if (asked && !root.has(field)) {
        throw new JsonMappingException(
            p,
            "Response is missing requested required column '"
                + field
                + "' — it was requested (or no columns filter was applied) but the API did not"
                + " return it");
      }
    }
  }
}
