package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.stocks.StockCandle;
import com.marketdata.sdk.stocks.StockCandles;
import com.marketdata.sdk.stocks.StockCandlesRequest;
import com.marketdata.sdk.stocks.StockEarning;
import com.marketdata.sdk.stocks.StockEarnings;
import com.marketdata.sdk.stocks.StockEarningsRequest;
import com.marketdata.sdk.stocks.StockNews;
import com.marketdata.sdk.stocks.StockNewsRequest;
import com.marketdata.sdk.stocks.StockPrice;
import com.marketdata.sdk.stocks.StockPrices;
import com.marketdata.sdk.stocks.StockPricesRequest;
import com.marketdata.sdk.stocks.StockQuote;
import com.marketdata.sdk.stocks.StockQuoteRequest;
import com.marketdata.sdk.stocks.StockQuotes;
import com.marketdata.sdk.stocks.StockQuotesRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Stocks endpoints ({@code /v1/stocks/...}). Reached through {@code client.stocks()}.
 *
 * <p>The resource is an <em>immutable configured value</em> (resource-architecture §1.3): the
 * universal-parameter setters ({@link #dateFormat}, {@link #mode}, {@link #limit}, {@link #offset},
 * {@link #columns}) each return a configured copy, so "configure once, call many" works and the
 * config carries into the {@link #asCsv()} facet. Every endpoint returns a named {@link
 * MarketDataResponse} whose {@link MarketDataResponse#values()} is the flat payload.
 *
 * <p>Constructor is package-private (ADR-007) — consumers cannot instantiate.
 */
public final class StocksResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;
  private final RequestConfig config;

  /** Client-facing constructor: registers the wire-format module once, starts with empty config. */
  StocksResource(HttpTransport transport, JsonResponseParser parser) {
    this(transport, parser, RequestConfig.empty());
    parser.registerModule(wireFormatModule());
  }

  private StocksResource(HttpTransport transport, JsonResponseParser parser, RequestConfig config) {
    this.transport = transport;
    this.parser = parser;
    this.config = config;
  }

  // ---------- universal parameters (type-preserving + columns) ----------

  /** Returns a copy that requests {@code dateformat} on every subsequent call. */
  public StocksResource dateFormat(DateFormat dateFormat) {
    return new StocksResource(transport, parser, config.withDateFormat(dateFormat));
  }

  /**
   * Returns a copy with the data-freshness {@code mode} (cached honored only by quote endpoints).
   */
  public StocksResource mode(Mode mode) {
    return new StocksResource(transport, parser, config.withMode(mode));
  }

  /** Returns a copy with the pagination {@code limit}. */
  public StocksResource limit(int limit) {
    return new StocksResource(transport, parser, config.withLimit(limit));
  }

  /** Returns a copy with the pagination {@code offset}. */
  public StocksResource offset(int offset) {
    return new StocksResource(transport, parser, config.withOffset(offset));
  }

  /**
   * Returns a copy that projects the response to the given columns (wire field names). Fields not
   * requested decode to {@code null}; a requested column the API fails to return surfaces as a
   * {@link com.marketdata.sdk.exception.ParseError} rather than a silent null.
   */
  public StocksResource columns(String... columns) {
    return new StocksResource(transport, parser, config.withColumns(List.of(columns)));
  }

  // ---------- format facet ----------

  /** A CSV-flavored view of this resource (carrying the same universal-param config). */
  public StocksCsvResource asCsv() {
    return new StocksCsvResource(transport, config);
  }

  /**
   * HTML facet — built but not exposed to consumers (the backend returns no HTML for any data
   * endpoint today). Package-private so it can be exercised by tests; flip to {@code public} when
   * the server supports {@code format=html}.
   */
  StocksHtmlResource asHtml() {
    return new StocksHtmlResource(transport, config);
  }

  // ---------- endpoints (typed) ----------

  /**
   * Async: fetch the OHLCV candle series for a single symbol.
   *
   * <p>Per SDK requirements §12, an <em>intraday</em> request with a {@code from} bound spanning
   * more than ~one year is auto-split into year-sized sub-requests, dispatched concurrently through
   * the transport's 50-permit pool and merged: the returned response's {@link
   * MarketDataResponse#values()} are every slice's candles concatenated in chronological order.
   * When splitting occurs, the response metadata ({@code statusCode}/{@code requestId}/{@code
   * json}/{@code rateLimit}) reflects the final sub-request.
   */
  public java.util.concurrent.CompletableFuture<StockCandlesResponse> candlesAsync(
      StockCandlesRequest request) {
    List<DateRange> chunks = candleChunks(request);
    if (chunks.size() == 1) {
      DateRange only = chunks.get(0);
      RequestSpec.Builder b = candlesSpec(request, only.from(), only.to());
      config.applyTo(b);
      return execute(
          b.build(),
          StockCandles.class,
          (d, env, fmt) -> new StockCandlesResponse(d.candles(), env, fmt));
    }
    List<java.util.concurrent.CompletableFuture<DecodedChunk>> futures =
        new java.util.ArrayList<>(chunks.size());
    for (DateRange range : chunks) {
      RequestSpec.Builder b = candlesSpec(request, range.from(), range.to());
      config.applyTo(b);
      RequestSpec spec = b.build();
      futures.add(
          transport
              .executeAsync(spec)
              .thenApply(
                  env ->
                      new DecodedChunk(
                          parser.parse(env, StockCandles.class, config.columns()),
                          env,
                          spec.format())));
    }
    return java.util.concurrent.CompletableFuture.allOf(
            futures.toArray(new java.util.concurrent.CompletableFuture<?>[0]))
        .thenApply(
            unused -> {
              List<StockCandle> merged = new java.util.ArrayList<>();
              DecodedChunk last = futures.get(futures.size() - 1).join();
              for (java.util.concurrent.CompletableFuture<DecodedChunk> f : futures) {
                merged.addAll(f.join().decoded().candles());
              }
              return new StockCandlesResponse(List.copyOf(merged), last.envelope(), last.format());
            });
  }

  /** One decoded candle sub-request — its rows plus the envelope/format for metadata. */
  private record DecodedChunk(StockCandles decoded, HttpResponseEnvelope envelope, Format format) {}

  /** Sync wrapper for {@link #candlesAsync(StockCandlesRequest)}. */
  public StockCandlesResponse candles(StockCandlesRequest request) {
    return transport.joinSync(candlesAsync(request));
  }

  /** Async: fetch the real-time quote for a single symbol. */
  public java.util.concurrent.CompletableFuture<StockQuotesResponse> quoteAsync(
      StockQuoteRequest request) {
    RequestSpec.Builder b =
        quoteSpec(request.symbol(), request.extended(), request.candle(), request.week52());
    config.applyTo(b);
    return execute(
        b.build(),
        StockQuotes.class,
        (d, env, fmt) -> new StockQuotesResponse(d.quotes(), env, fmt));
  }

  /** Sync wrapper for {@link #quoteAsync(StockQuoteRequest)}. */
  public StockQuotesResponse quote(StockQuoteRequest request) {
    return transport.joinSync(quoteAsync(request));
  }

  /**
   * Async: fetch real-time quotes for multiple symbols in a <em>single</em> request (the backend
   * accepts a comma list), returning one row per symbol in the response.
   */
  public java.util.concurrent.CompletableFuture<StockQuotesResponse> quotesAsync(
      StockQuotesRequest request) {
    RequestSpec.Builder b =
        quotesSpec(request.symbols(), request.extended(), request.candle(), request.week52());
    config.applyTo(b);
    return execute(
        b.build(),
        StockQuotes.class,
        (d, env, fmt) -> new StockQuotesResponse(d.quotes(), env, fmt));
  }

  /** Sync wrapper for {@link #quotesAsync(StockQuotesRequest)}. */
  public StockQuotesResponse quotes(StockQuotesRequest request) {
    return transport.joinSync(quotesAsync(request));
  }

  /** Async: fetch the last price for multiple symbols in a single request. */
  public java.util.concurrent.CompletableFuture<StockPricesResponse> pricesAsync(
      StockPricesRequest request) {
    RequestSpec.Builder b = pricesSpec(request.symbols());
    config.applyTo(b);
    return execute(
        b.build(),
        StockPrices.class,
        (d, env, fmt) -> new StockPricesResponse(d.prices(), env, fmt));
  }

  /** Sync wrapper for {@link #pricesAsync(StockPricesRequest)}. */
  public StockPricesResponse prices(StockPricesRequest request) {
    return transport.joinSync(pricesAsync(request));
  }

  /** Async: fetch news articles for a single symbol. */
  public java.util.concurrent.CompletableFuture<StockNewsResponse> newsAsync(
      StockNewsRequest request) {
    RequestSpec.Builder b = newsSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        StockNews.class,
        (d, env, fmt) -> new StockNewsResponse(d.articles(), d.updated(), env, fmt));
  }

  /** Sync wrapper for {@link #newsAsync(StockNewsRequest)}. */
  public StockNewsResponse news(StockNewsRequest request) {
    return transport.joinSync(newsAsync(request));
  }

  /** Async: fetch the earnings history (or forward calendar) for a single symbol. */
  public java.util.concurrent.CompletableFuture<StockEarningsResponse> earningsAsync(
      StockEarningsRequest request) {
    RequestSpec.Builder b = earningsSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        StockEarnings.class,
        (d, env, fmt) -> new StockEarningsResponse(d.earnings(), env, fmt));
  }

  /** Sync wrapper for {@link #earningsAsync(StockEarningsRequest)}. */
  public StockEarningsResponse earnings(StockEarningsRequest request) {
    return transport.joinSync(earningsAsync(request));
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

  static RequestSpec.Builder candlesSpec(StockCandlesRequest r) {
    return candlesSpec(r, r.from(), r.to());
  }

  /**
   * Candles spec with the date window overridden — used by the auto-chunking path (§12), where each
   * sub-request carries a slice of the original {@code from}/{@code to} range. Non-window params
   * ({@code date}/{@code countback}/exchange/etc.) come from {@code r} unchanged.
   */
  static RequestSpec.Builder candlesSpec(
      StockCandlesRequest r, @Nullable LocalDate from, @Nullable LocalDate to) {
    RequestSpec.Builder b =
        RequestSpec.get(
            "stocks/candles/"
                + PathSegments.encode(r.resolution().wireValue())
                + "/"
                + PathSegments.encode(r.symbol()));
    if (r.date() != null) {
      b.query("date", DateTimeFormatter.ISO_LOCAL_DATE.format(r.date()));
    }
    if (from != null) {
      b.query("from", DateTimeFormatter.ISO_LOCAL_DATE.format(from));
    }
    if (to != null) {
      b.query("to", DateTimeFormatter.ISO_LOCAL_DATE.format(to));
    }
    if (r.countback() != null) {
      b.query("countback", r.countback());
    }
    if (r.exchange() != null) {
      b.query("exchange", r.exchange());
    }
    if (r.extended() != null) {
      b.query("extended", r.extended());
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

  /** Inclusive-{@code from} / exclusive-{@code to} date window for one candle sub-request. */
  record DateRange(@Nullable LocalDate from, @Nullable LocalDate to) {}

  /** Year-sized chunk span: the API caps a single intraday candle request to ~one year. */
  private static final int CHUNK_DAYS = 365;

  /**
   * Splits a candle request's date window into the sub-ranges to fetch (§12). Returns a single
   * range (the request's own window) unless the resolution is intraday <em>and</em> a {@code from}
   * bound is set — then the {@code [from, to]} span (with {@code to} defaulting to today when
   * open-ended) is cut into consecutive ≤{@value #CHUNK_DAYS}-day ranges. Mirrors the Python SDK's
   * {@code split_dates_by_timeframe}: contiguous, non-overlapping ({@code to} of one chunk is the
   * {@code from} of the next, and {@code to} is exclusive so the boundary candle isn't duplicated).
   */
  static List<DateRange> candleChunks(StockCandlesRequest r) {
    LocalDate from = r.from();
    if (from == null || !r.resolution().isIntraday()) {
      return List.of(new DateRange(from, r.to()));
    }
    LocalDate to = r.to() != null ? r.to() : LocalDate.now();
    if (!from.isBefore(to)) {
      return List.of(new DateRange(from, r.to())); // degenerate — let the backend handle it
    }
    List<DateRange> ranges = new java.util.ArrayList<>();
    LocalDate current = from;
    while (true) {
      LocalDate nextCut = current.plusDays(CHUNK_DAYS);
      if (!nextCut.isBefore(to)) { // nextCut >= to
        ranges.add(new DateRange(current, to));
        break;
      }
      ranges.add(new DateRange(current, nextCut));
      current = nextCut;
    }
    return ranges;
  }

  static RequestSpec.Builder quoteSpec(
      String symbol,
      @Nullable Boolean extended,
      @Nullable Boolean candle,
      @Nullable Boolean week52) {
    RequestSpec.Builder b = RequestSpec.get("stocks/quotes/" + PathSegments.encode(symbol));
    applyQuoteFlags(b, extended, candle, week52);
    return b;
  }

  static RequestSpec.Builder quotesSpec(
      List<String> symbols,
      @Nullable Boolean extended,
      @Nullable Boolean candle,
      @Nullable Boolean week52) {
    RequestSpec.Builder b = RequestSpec.get("stocks/quotes");
    b.query("symbols", String.join(",", symbols));
    applyQuoteFlags(b, extended, candle, week52);
    return b;
  }

  private static void applyQuoteFlags(
      RequestSpec.Builder b,
      @Nullable Boolean extended,
      @Nullable Boolean candle,
      @Nullable Boolean week52) {
    if (extended != null) {
      b.query("extended", extended);
    }
    if (candle != null) {
      b.query("candle", candle);
    }
    if (week52 != null) {
      b.query("52week", week52);
    }
  }

  static RequestSpec.Builder pricesSpec(List<String> symbols) {
    RequestSpec.Builder b = RequestSpec.get("stocks/prices");
    b.query("symbols", String.join(",", symbols));
    return b;
  }

  static RequestSpec.Builder newsSpec(StockNewsRequest r) {
    RequestSpec.Builder b = RequestSpec.get("stocks/news/" + PathSegments.encode(r.symbol()));
    applyWindow(b, r.date(), r.from(), r.to(), r.countback());
    return b;
  }

  static RequestSpec.Builder earningsSpec(StockEarningsRequest r) {
    RequestSpec.Builder b = RequestSpec.get("stocks/earnings/" + PathSegments.encode(r.symbol()));
    applyWindow(b, r.date(), r.from(), r.to(), r.countback());
    if (r.report() != null) {
      b.query("report", r.report());
    }
    return b;
  }

  private static void applyWindow(
      RequestSpec.Builder b,
      @Nullable LocalDate date,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable Integer countback) {
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
  }

  // ---------- wire-format module ----------

  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-stocks");
    m.addDeserializer(
        StockCandles.class,
        rowsDeserializer(
            CANDLE_FIELDS, CANDLE_FIELDS, StocksResource::buildCandleRow, StockCandles::new));
    m.addDeserializer(
        StockQuotes.class,
        rowsDeserializer(
            QUOTE_FIELDS, QUOTE_REQUIRED_FIELDS, StocksResource::buildQuoteRow, StockQuotes::new));
    m.addDeserializer(
        StockPrices.class,
        rowsDeserializer(
            PRICE_FIELDS, PRICE_FIELDS, StocksResource::buildPriceRow, StockPrices::new));
    m.addDeserializer(
        StockEarnings.class,
        rowsDeserializer(
            EARNINGS_FIELDS,
            EARNINGS_REQUIRED_FIELDS,
            StocksResource::buildEarningRow,
            StockEarnings::new));
    m.addDeserializer(StockNews.class, new StockNewsDeserializer());
    return m;
  }

  // candle columns: every column required (any may be projected away via `columns`).
  private static final List<String> CANDLE_FIELDS = List.of("t", "o", "h", "l", "c", "v");

  // quote columns: the always-emitted set is required; OHLC + 52-week extremes are opt-in.
  private static final List<String> QUOTE_REQUIRED_FIELDS =
      List.of(
          "symbol",
          "ask",
          "askSize",
          "bid",
          "bidSize",
          "mid",
          "last",
          "change",
          "changepct",
          "volume",
          "updated");
  private static final List<String> QUOTE_FIELDS =
      List.of(
          "symbol",
          "ask",
          "askSize",
          "bid",
          "bidSize",
          "mid",
          "last",
          "change",
          "changepct",
          "volume",
          "updated",
          "o",
          "h",
          "l",
          "c",
          "52weekHigh",
          "52weekLow");

  private static final List<String> PRICE_FIELDS =
      List.of("symbol", "mid", "change", "changepct", "updated");

  private static final List<String> EARNINGS_FIELDS =
      List.of(
          "symbol",
          "fiscalYear",
          "fiscalQuarter",
          "date",
          "reportDate",
          "reportTime",
          "currency",
          "reportedEPS",
          "estimatedEPS",
          "surpriseEPS",
          "surpriseEPSpct",
          "updated");
  // Identity/timing columns the backend always emits; the rest are legitimately null on
  // future-quarter or fundamentals-missing rows.
  private static final List<String> EARNINGS_REQUIRED_FIELDS = List.of("symbol", "date", "updated");

  private static StockCandle buildCandleRow(ParallelArrays.Row row) throws IOException {
    return new StockCandle(
        dateOrTimestampOrNull(row, "t"),
        row.dblOrNull("o"),
        row.dblOrNull("h"),
        row.dblOrNull("l"),
        row.dblOrNull("c"),
        row.lngOrNull("v"));
  }

  private static StockQuote buildQuoteRow(ParallelArrays.Row row) throws IOException {
    return new StockQuote(
        row.textOrNull("symbol"),
        row.dblOrNull("ask"),
        row.lngOrNull("askSize"),
        row.dblOrNull("bid"),
        row.lngOrNull("bidSize"),
        row.dblOrNull("mid"),
        row.dblOrNull("last"),
        row.dblOrNull("change"),
        row.dblOrNull("changepct"),
        row.lngOrNull("volume"),
        timestampOrNull(row, "updated"),
        row.dblOrNull("o"),
        row.dblOrNull("h"),
        row.dblOrNull("l"),
        row.dblOrNull("c"),
        row.dblOrNull("52weekHigh"),
        row.dblOrNull("52weekLow"));
  }

  private static StockPrice buildPriceRow(ParallelArrays.Row row) throws IOException {
    return new StockPrice(
        row.textOrNull("symbol"),
        row.dblOrNull("mid"),
        row.dblOrNull("change"),
        row.dblOrNull("changepct"),
        timestampOrNull(row, "updated"));
  }

  private static StockEarning buildEarningRow(ParallelArrays.Row row) throws IOException {
    return new StockEarning(
        row.textOrNull("symbol"),
        intOrNull(row.lngOrNull("fiscalYear")),
        intOrNull(row.lngOrNull("fiscalQuarter")),
        dateOrTimestampOrNull(row, "date"),
        dateOrTimestampOrNull(row, "reportDate"),
        row.textOrNull("reportTime"),
        row.textOrNull("currency"),
        row.dblOrNull("reportedEPS"),
        row.dblOrNull("estimatedEPS"),
        row.dblOrNull("surpriseEPS"),
        row.dblOrNull("surpriseEPSpct"),
        timestampOrNull(row, "updated"));
  }

  private static @Nullable ZonedDateTime timestampOrNull(ParallelArrays.Row row, String field)
      throws IOException {
    JsonNode n = row.nodeOrNull(field);
    return n == null ? null : MarketDataDates.parseTimestampField(null, n, field);
  }

  private static @Nullable ZonedDateTime dateOrTimestampOrNull(ParallelArrays.Row row, String field)
      throws IOException {
    JsonNode n = row.nodeOrNull(field);
    return n == null ? null : MarketDataDates.parseDateOrTimestampField(null, n, field);
  }

  private static @Nullable Integer intOrNull(@Nullable Long v) {
    return v == null ? null : v.intValue();
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
