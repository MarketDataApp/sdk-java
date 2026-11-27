package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import com.marketdata.sdk.markets.MarketStatuses;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Markets endpoints ({@code /v1/markets/...}). Reached through {@code client.markets()}.
 *
 * <p>The single endpoint, {@code status}, answers "was/is the market open on these days?" from the
 * exchange holiday calendar — distinct from {@code client.utilities().status()}, which reports the
 * <em>API's own</em> per-service health from the unversioned {@code /status/} route.
 *
 * <p>The resource is an <em>immutable configured value</em> (resource-architecture §1.3): the
 * universal-parameter setters ({@link #dateFormat}, {@link #mode}, {@link #limit}, {@link #offset},
 * {@link #columns}) each return a configured copy, so "configure once, call many" works and the
 * config carries into the {@link #asCsv()} facet. Every endpoint returns a named {@link
 * MarketDataResponse} whose {@link MarketDataResponse#values()} is the flat payload.
 *
 * <p>Constructor is package-private (ADR-007) — consumers cannot instantiate.
 */
public final class MarketsResource extends ConfiguredResource<MarketsResource> {

  private final JsonResponseParser parser;

  /** Client-facing constructor: registers the wire-format module once, starts with empty config. */
  MarketsResource(HttpTransport transport, JsonResponseParser parser) {
    this(transport, parser, RequestConfig.empty());
    parser.registerModule(wireFormatModule());
  }

  private MarketsResource(
      HttpTransport transport, JsonResponseParser parser, RequestConfig config) {
    super(transport, config);
    this.parser = parser;
  }

  // ---------- universal parameters: inherited from ConfiguredResource ----------

  @Override
  MarketsResource withConfig(RequestConfig config) {
    return new MarketsResource(transport, parser, config);
  }

  // ---------- format facet ----------

  /** A CSV-flavored view of this resource (carrying the same universal-param config). */
  public MarketsCsvResource asCsv() {
    return new MarketsCsvResource(transport, config);
  }

  /**
   * HTML facet — built but not exposed to consumers (the backend returns no HTML for any data
   * endpoint today). Package-private so it can be exercised by tests; flip to {@code public} when
   * the server supports {@code format=html}.
   */
  MarketsHtmlResource asHtml() {
    return new MarketsHtmlResource(transport, config);
  }

  // ---------- endpoints (typed) ----------

  /** Async: fetch the open/closed status of one day or a range of days. */
  public java.util.concurrent.CompletableFuture<MarketStatusResponse> statusAsync(
      MarketStatusRequest request) {
    RequestSpec.Builder b = statusSpec(request);
    config.applyTo(b);
    return execute(
        b.build(),
        MarketStatuses.class,
        (d, env, fmt) -> new MarketStatusResponse(d.statuses(), env, fmt));
  }

  /** Sync wrapper for {@link #statusAsync(MarketStatusRequest)}. */
  public MarketStatusResponse status(MarketStatusRequest request) {
    return transport.joinSync(statusAsync(request));
  }

  // ---------- execute ----------

  private <D, R> java.util.concurrent.CompletableFuture<R> execute(
      RequestSpec spec, Class<D> decodeType, ResponseFactory<D, R> factory) {
    return JsonResponses.execute(transport, parser, spec, config.columns(), decodeType, factory);
  }

  // ---------- request spec builders (package-private static — reused by the facets) ----------

  static RequestSpec.Builder statusSpec(MarketStatusRequest r) {
    RequestSpec.Builder b = RequestSpec.get("markets/status");
    if (r.country() != null) {
      b.query("country", r.country());
    }
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
    return b;
  }

  // ---------- wire-format module ----------

  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-markets");
    m.addDeserializer(
        MarketStatuses.class,
        rowsDeserializer(
            STATUS_FIELDS, STATUS_FIELDS, MarketsResource::buildStatusRow, MarketStatuses::new));
    return m;
  }

  // status columns: both required (either may be projected away via `columns`). A `status` CELL
  // can still be null — the backend emits null for days outside its holiday-calendar coverage.
  private static final List<String> STATUS_FIELDS = List.of("date", "status");

  private static MarketStatus buildStatusRow(ParallelArrays.Row row) throws IOException {
    return new MarketStatus(dateOrTimestampOrNull(row, "date"), row.textOrNull("status"));
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
   * it away" (or, for {@code status} cells, "outside calendar coverage") — never "the backend
   * silently dropped the column".
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
