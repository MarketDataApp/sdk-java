package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.funds.FundCandle;
import com.marketdata.sdk.funds.FundCandles;
import com.marketdata.sdk.funds.FundCandlesRequest;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Funds endpoints ({@code /v1/funds/...}). Reached through {@code client.funds()}.
 *
 * <p>The resource is an <em>immutable configured value</em> (resource-architecture §1.3): the
 * universal-parameter setters ({@link #dateFormat}, {@link #mode}, {@link #limit}, {@link #offset},
 * {@link #columns}) each return a configured copy, so "configure once, call many" works and the
 * config carries into the {@link #asCsv()} facet. Every endpoint returns a named {@link
 * MarketDataResponse} whose {@link MarketDataResponse#values()} is the flat payload.
 *
 * <p>Unlike {@code stocks.candles}, there is no §12 auto-chunking here: funds serve no intraday
 * resolutions (the API rejects them), and only intraday requests are subject to the ~one-year span
 * cap that chunking works around.
 *
 * <p>Constructor is package-private (ADR-007) — consumers cannot instantiate.
 */
public final class FundsResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;
  private final RequestConfig config;

  /** Client-facing constructor: registers the wire-format module once, starts with empty config. */
  FundsResource(HttpTransport transport, JsonResponseParser parser) {
    this(transport, parser, RequestConfig.empty());
    parser.registerModule(wireFormatModule());
  }

  private FundsResource(HttpTransport transport, JsonResponseParser parser, RequestConfig config) {
    this.transport = transport;
    this.parser = parser;
    this.config = config;
  }

  // ---------- universal parameters (type-preserving + columns) ----------

  /** Returns a copy that requests {@code dateformat} on every subsequent call. */
  public FundsResource dateFormat(DateFormat dateFormat) {
    return new FundsResource(transport, parser, config.withDateFormat(dateFormat));
  }

  /** Returns a copy with the data-freshness {@code mode}. */
  public FundsResource mode(Mode mode) {
    return new FundsResource(transport, parser, config.withMode(mode));
  }

  /** Returns a copy with the pagination {@code limit}. */
  public FundsResource limit(int limit) {
    return new FundsResource(transport, parser, config.withLimit(limit));
  }

  /** Returns a copy with the pagination {@code offset}. */
  public FundsResource offset(int offset) {
    return new FundsResource(transport, parser, config.withOffset(offset));
  }

  /**
   * Returns a copy that projects the response to the given columns (wire field names). Fields not
   * requested decode to {@code null}; a requested column the API fails to return surfaces as a
   * {@link com.marketdata.sdk.exception.ParseError} rather than a silent null.
   */
  public FundsResource columns(String... columns) {
    return new FundsResource(transport, parser, config.withColumns(List.of(columns)));
  }

  // ---------- format facet ----------

  /** A CSV-flavored view of this resource (carrying the same universal-param config). */
  public FundsCsvResource asCsv() {
    return new FundsCsvResource(transport, config);
  }

  /**
   * HTML facet — built but not exposed to consumers (the backend returns no HTML for any data
   * endpoint today). Package-private so it can be exercised by tests; flip to {@code public} when
   * the server supports {@code format=html}.
   */
  FundsHtmlResource asHtml() {
    return new FundsHtmlResource(transport, config);
  }

  // ---------- endpoints (typed) ----------

  /** Async: fetch the OHLC candle series for a single fund. */
  public java.util.concurrent.CompletableFuture<FundCandlesResponse> candlesAsync(
      FundCandlesRequest request) {
    RequestSpec.Builder b = candlesSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        FundCandles.class,
        (d, env, fmt) -> new FundCandlesResponse(d.candles(), env, fmt));
  }

  /** Sync wrapper for {@link #candlesAsync(FundCandlesRequest)}. */
  public FundCandlesResponse candles(FundCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  // ---------- execute ----------

  private <D, R> java.util.concurrent.CompletableFuture<R> execute(
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

  static RequestSpec.Builder candlesSpec(FundCandlesRequest r) {
    RequestSpec.Builder b =
        RequestSpec.get(
            "funds/candles/"
                + PathSegments.encode(r.resolution().wireValue())
                + "/"
                + PathSegments.encode(r.symbol()));
    if (r.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(r.date()));
    }
    if (r.from() != null) {
      b.query("from", DateTimeFormatter.ISO_LOCAL_DATE.format(r.from()));
    }
    if (r.to() != null) {
      b.query("to", DateTimeFormatter.ISO_LOCAL_DATE.format(r.to()));
    }
    if (r.countback() != null) {
      b.query("countback", r.countback());
    }
    if (r.exchange() != null) {
      b.query("exchange", r.exchange());
    }
    if (r.country() != null) {
      b.query("country", r.country());
    }
    if (r.adjustSplits() != null) {
      b.query("adjustsplits", r.adjustSplits());
    }
    if (r.adjustDividends() != null) {
      b.query("adjustdividends", r.adjustDividends());
    }
    return b;
  }

  // ---------- wire-format module ----------

  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-funds");
    m.addDeserializer(
        FundCandles.class,
        rowsDeserializer(
            CANDLE_FIELDS, CANDLE_FIELDS, FundsResource::buildCandleRow, FundCandles::new));
    return m;
  }

  // candle columns: every column required (any may be projected away via `columns`). Funds carry
  // no volume column — NAV-based series have nothing traded to count.
  private static final List<String> CANDLE_FIELDS = List.of("t", "o", "h", "l", "c");

  private static FundCandle buildCandleRow(ParallelArrays.Row row) throws IOException {
    return new FundCandle(
        dateOrTimestampOrNull(row, "t"),
        row.dblOrNull("o"),
        row.dblOrNull("h"),
        row.dblOrNull("l"),
        row.dblOrNull("c"));
  }

  private static @Nullable ZonedDateTime dateOrTimestampOrNull(ParallelArrays.Row row, String field)
      throws IOException {
    JsonNode n = row.nodeOrNull(field);
    return n == null ? null : MarketDataDates.parseDateOrTimestampField(null, n, field);
  }

  /**
   * Builds a parallel-arrays deserializer where every column is optional at the wire level (so a
   * {@code columns} projection decodes cleanly to nulls), restoring the strict guarantee via {@link
   * #validateRequestedColumns} (Option A): a <em>requested</em> required column the API omitted
   * surfaces as a {@code ParseError} instead of a silent null.
   */
  private static <ROW, T> JsonDeserializer<T> rowsDeserializer(
      List<String> allFields,
      List<String> requiredFields,
      ParallelArrays.RowBuilder<ROW> rowBuilder,
      Function<List<ROW>, T> wrapper) {
    return new JsonDeserializer<>() {
      @Override
      public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode root = p.readValueAsTree();
        List<ROW> rows = ParallelArrays.zip(p, root, List.of(), allFields, rowBuilder);
        validateRequestedColumns(p, root, rows.size(), ctxt, requiredFields);
        return wrapper.apply(rows);
      }
    };
  }

  /**
   * Option A: for every required column the consumer asked for (explicitly via {@code columns}, or
   * implicitly by not projecting at all), verify the API actually returned it. A requested-but-
   * absent required column throws, so a {@code null} a consumer sees only ever means "I projected
   * it away" — never "the backend silently dropped it".
   */
  private static void validateRequestedColumns(
      JsonParser p,
      JsonNode root,
      int rowCount,
      DeserializationContext ctxt,
      List<String> requiredFields)
      throws JsonMappingException {
    if (rowCount == 0) {
      return; // no_data / empty response — no projection to validate
    }
    Object attr = ctxt.getAttribute(JsonResponseParser.REQUESTED_COLUMNS_ATTR);
    List<String> requested =
        attr instanceof List<?> list
            ? list.stream().map(String::valueOf).collect(Collectors.toList())
            : List.of();
    for (String field : requiredFields) {
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
