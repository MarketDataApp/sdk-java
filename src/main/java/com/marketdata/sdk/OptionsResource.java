package com.marketdata.sdk;

import com.fasterxml.jackson.databind.JsonDeserializer;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
    m.addDeserializer(OptionsStrikes.class, new OptionsStrikesDeserializer());
    m.addDeserializer(OptionsQuotes.class, optionRowsDeserializer(OptionsQuotes::new));
    m.addDeserializer(OptionsChain.class, optionRowsDeserializer(OptionsChain::new));
    return m;
  }

  /** Column list for the shared {@code OptionQuote} parallel-arrays row, used by both endpoints. */
  private static final List<String> OPTION_ROW_FIELDS =
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
          "vega");

  /**
   * Optional columns on the option row. {@code rho} is part of the documented schema but several
   * feeds omit it (the API's own fixtures don't carry it); declaring it optional lets a response
   * without rho decode cleanly to {@link OptionQuote#rho()} == {@code null} instead of raising a
   * {@link com.marketdata.sdk.exception.ParseError} on a missing required column.
   */
  private static final List<String> OPTION_OPTIONAL_ROW_FIELDS = List.of("rho");

  /**
   * Shared parallel-arrays deserializer that maps the API's option row into {@link OptionQuote}.
   * Reused by both {@link OptionsQuotes} and {@link OptionsChain} since they emit the same
   * per-contract schema; only the container record (and the semantic of how many rows come back)
   * differs.
   */
  private static <T> JsonDeserializer<T> optionRowsDeserializer(
      Function<List<OptionQuote>, T> wrapper) {
    return ParallelArrays.listDeserializer(
        OPTION_ROW_FIELDS,
        OPTION_OPTIONAL_ROW_FIELDS,
        row ->
            new OptionQuote(
                row.text("optionSymbol"),
                row.text("underlying"),
                MarketDataDates.parseTimestampField(null, row.node("expiration"), "expiration"),
                row.text("side"),
                row.dbl("strike"),
                MarketDataDates.parseTimestampField(null, row.node("firstTraded"), "firstTraded"),
                (int) row.lng("dte"),
                MarketDataDates.parseTimestampField(null, row.node("updated"), "updated"),
                row.dbl("bid"),
                row.lng("bidSize"),
                row.dbl("mid"),
                row.dbl("ask"),
                row.lng("askSize"),
                row.dbl("last"),
                row.lng("openInterest"),
                row.lng("volume"),
                row.bool("inTheMoney"),
                row.dbl("intrinsicValue"),
                row.dbl("extrinsicValue"),
                row.dbl("underlyingPrice"),
                row.dbl("iv"),
                row.dbl("delta"),
                row.dbl("gamma"),
                row.dbl("theta"),
                row.dbl("vega"),
                row.dblOrNull("rho")),
        wrapper);
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

  /**
   * Async: fetch the strike prices available for each expiration on the request's underlying.
   * Optional filters: {@code expiration} returns strikes only for that expiration date, {@code
   * date} fetches the historical table as it stood on a previous trading day.
   *
   * <p>The wire-format is unusual — one top-level key per expiration date plus {@code s} / {@code
   * updated} metadata — and the deserializer accepts only the API's literal ISO date keys, not the
   * §3 {@code dateformat} variants (the keys themselves cannot vary: the backend always emits
   * {@code str(date)}). The {@code updated} field does honor {@code dateformat}.
   */
  public CompletableFuture<Response<OptionsStrikes>> strikesAsync(OptionsStrikesRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/strikes/" + PathSegments.encode(request.symbol()));
    if (request.expiration() != null) {
      b.query("expiration", DateTimeFormatter.ISO_LOCAL_DATE.format(request.expiration()));
    }
    if (request.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(request.date()));
    }
    return executeAndWrap(b.build(), OptionsStrikes.class);
  }

  /** Sync wrapper for {@link #strikesAsync(OptionsStrikesRequest)}. */
  public Response<OptionsStrikes> strikes(OptionsStrikesRequest request) {
    return transport.joinSync(strikesAsync(request));
  }

  /**
   * Async: fetch the current (or historical) quote for a single OCC option symbol. The
   * parallel-arrays wire-format still applies — typically a single row — and is decoded into {@link
   * OptionsQuotes#quotes}.
   *
   * <p>For multiple contracts use {@link #quotesAsync(OptionsQuotesRequest)} — the multi-symbol
   * form fans out one HTTP call per symbol concurrently through the SDK's 50-permit semaphore and
   * returns a per-symbol map.
   */
  public CompletableFuture<Response<OptionsQuotes>> quoteAsync(OptionsQuoteRequest request) {
    return executeAndWrap(
        buildQuoteSpec(request.optionSymbol(), request.date(), request.from(), request.to()),
        OptionsQuotes.class);
  }

  /** Sync wrapper for {@link #quoteAsync(OptionsQuoteRequest)}. */
  public Response<OptionsQuotes> quote(OptionsQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  /**
   * Async: fetch quotes for multiple OCC option symbols concurrently. One HTTP request is fired per
   * symbol — the API path takes a single optionSymbol so comma-separated bulk isn't actually
   * supported by the backend regardless of what the docstring says (verified by reading the
   * handler). All requests share the same optional {@code date}/{@code from}/{@code to} filters.
   *
   * <p>Returns a {@code Map<String, Response<OptionsQuotes>>} keyed by the original symbol input
   * (insertion order preserved) so the consumer sees per-symbol {@link Response} metadata — {@code
   * statusCode()}, {@code isNoData()}, {@code rawBody()}, {@code requestId()}. The map's future
   * completes exceptionally if any single request fails (network error, ParseError on a malformed
   * body, a {@code 5xx} after retries) — fail-fast semantics so partial-success scenarios are
   * explicit.
   */
  public CompletableFuture<Map<String, Response<OptionsQuotes>>> quotesAsync(
      OptionsQuotesRequest request) {
    List<String> symbols = request.optionSymbols();
    List<CompletableFuture<Map.Entry<String, Response<OptionsQuotes>>>> futures =
        new ArrayList<>(symbols.size());
    for (String symbol : symbols) {
      RequestSpec spec = buildQuoteSpec(symbol, request.date(), request.from(), request.to());
      futures.add(
          executeAndWrap(spec, OptionsQuotes.class).thenApply(resp -> Map.entry(symbol, resp)));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            unused -> {
              Map<String, Response<OptionsQuotes>> result = new LinkedHashMap<>();
              for (CompletableFuture<Map.Entry<String, Response<OptionsQuotes>>> f : futures) {
                Map.Entry<String, Response<OptionsQuotes>> entry = f.join();
                result.put(entry.getKey(), entry.getValue());
              }
              return result;
            });
  }

  /** Sync wrapper for {@link #quotesAsync(OptionsQuotesRequest)}. */
  public Map<String, Response<OptionsQuotes>> quotes(OptionsQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  /**
   * Async: fetch the full option chain for the request's underlying. The chain endpoint exposes the
   * richest filter surface in the API; see {@link OptionsChainRequest} for the typed parameter set,
   * including sealed {@link ExpirationFilter} and {@link StrikeFilter} for the mutually-exclusive
   * groups.
   */
  public CompletableFuture<Response<OptionsChain>> chainAsync(OptionsChainRequest request) {
    RequestSpec.Builder b =
        RequestSpec.get("options/chain/" + PathSegments.encode(request.symbol()));
    applyChainParams(b, request);
    return executeAndWrap(b.build(), OptionsChain.class);
  }

  /** Sync wrapper for {@link #chainAsync(OptionsChainRequest)}. */
  public Response<OptionsChain> chain(OptionsChainRequest request) {
    return transport.joinSync(chainAsync(request));
  }

  // ---------- internal helpers ----------

  /** Translates a fully-built {@link OptionsChainRequest} into query parameters. */
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

  /**
   * Render a strike price without trailing zeros — the API accepts both {@code 150} and {@code
   * 150.0}.
   */
  private static String formatStrike(double v) {
    if (v == Math.floor(v) && !Double.isInfinite(v)) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }

  private static RequestSpec buildQuoteSpec(
      String optionSymbol,
      @Nullable LocalDate date,
      @Nullable LocalDate from,
      @Nullable LocalDate to) {
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
    return b.build();
  }

  private <T> CompletableFuture<Response<T>> executeAndWrap(RequestSpec spec, Class<T> type) {
    return transport
        .executeAsync(spec)
        .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
  }
}
