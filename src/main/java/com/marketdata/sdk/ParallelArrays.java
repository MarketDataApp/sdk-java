package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Helper for deserializing the API's parallel-arrays wire format. Almost every endpoint that
 * returns multiple rows uses this shape: N equal-length arrays of column values plus a leading
 * {@code "s"} envelope status, e.g.
 *
 * <pre>{@code
 * { "s": "ok",
 *   "symbol": ["AAPL", "MSFT"],
 *   "price":  [150.0,   400.0] }
 * }</pre>
 *
 * <p>{@link #zip} encapsulates the repeating boilerplate — envelope-error check, presence and
 * length validation across columns, indexed iteration — leaving each deserializer to declare just
 * <em>which</em> columns it expects and <em>how</em> to build one row from a {@link Row}.
 *
 * <p>Cell-level accessors on {@link Row} are <strong>strict by default</strong>: a {@code null}
 * cell or a value of the wrong JSON type raises {@link JsonMappingException} (which surfaces as
 * {@link com.marketdata.sdk.exception.ParseError} upstream). The previous lenient behavior —
 * substituting {@code ""}, {@code false}, {@code 0.0}, {@code 0} for missing cells — masked real
 * server bugs: e.g. a regression that dropped the {@code online} column would have silently flipped
 * every service to {@code online=false}, propagating to {@link StatusCache} decisions and blocking
 * retries across the board. If a future endpoint has legitimately-nullable columns, add explicit
 * {@code textOr(field, default)} overloads then — not pre-emptively.
 */
final class ParallelArrays {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_NO_DATA = "no_data";
  private static final String ENVELOPE_ERROR = "error";

  private ParallelArrays() {}

  /**
   * Zip the parallel arrays under {@code root} into a list of rows via {@code rowBuilder}.
   *
   * <p>Envelope handling:
   *
   * <ul>
   *   <li>{@code "s":"error"} → {@link JsonMappingException} carrying the server-side {@code
   *       errmsg}. The parent parser turns it into a {@link
   *       com.marketdata.sdk.exception.ParseError}.
   *   <li>{@code "s":"no_data"} → empty list. The backend uses this envelope (paired with HTTP 404,
   *       see {@code HttpTransport.routeAndEnvelope}) for "the query has no results"; the data
   *       arrays are deliberately omitted in that case. Returning an empty list lets the resource
   *       wrap it in its container type ({@code new ApiStatus(emptyList)}, etc.) so the consumer
   *       reaches {@link MarketDataResponse#isNoData()} and {@link MarketDataResponse#values()}
   *       normally instead of hitting a spurious {@code "missing field"} error from the
   *       field-validation loop.
   *   <li>Any other status (typically {@code "ok"}) → normal field validation.
   * </ul>
   *
   * @throws JsonMappingException if the envelope reports {@code "s":"error"}, a required field is
   *     absent or not an array, or arrays have mismatched lengths.
   */
  static <T> List<T> zip(JsonParser p, JsonNode root, List<String> fields, RowBuilder<T> rowBuilder)
      throws IOException {
    return zip(p, root, fields, List.of(), rowBuilder);
  }

  /**
   * Same as {@link #zip(JsonParser, JsonNode, List, RowBuilder)} but with a set of
   * <em>optional</em> columns layered on top of the required {@code fields}. An optional column
   * that is absent, null, or non-array is simply skipped (no error); when present it is
   * length-checked against the required columns like any other. The row builder reads optional
   * cells through {@link Row#dblOrNull} (and future {@code …OrNull} accessors), which return {@code
   * null} for an absent column or null cell instead of throwing — the strict-by-default accessors
   * stay strict for required columns.
   *
   * <p>This is the escape hatch the class doc anticipates for "legitimately-nullable columns": e.g.
   * the options {@code rho} greek, which several feeds omit entirely.
   */
  static <T> List<T> zip(
      JsonParser p,
      JsonNode root,
      List<String> fields,
      List<String> optionalFields,
      RowBuilder<T> rowBuilder)
      throws IOException {

    String envelopeStatus = root.path(ENVELOPE_STATUS).asText("");
    if (ENVELOPE_ERROR.equals(envelopeStatus)) {
      String errmsg = root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)");
      throw new JsonMappingException(p, "API responded with error: " + errmsg);
    }
    if (ENVELOPE_NO_DATA.equals(envelopeStatus)) {
      return List.of();
    }

    Map<String, JsonNode> arrays = new LinkedHashMap<>();
    int expected = -1;
    for (String field : fields) {
      JsonNode node = root.get(field);
      if (node == null || !node.isArray()) {
        throw new JsonMappingException(p, "missing or non-array field: " + field);
      }
      if (expected == -1) {
        expected = node.size();
      } else if (node.size() != expected) {
        throw new JsonMappingException(
            p,
            "mismatched lengths: "
                + field
                + "="
                + node.size()
                + " vs expected="
                + expected
                + " (from first column "
                + fields.get(0)
                + ")");
      }
      arrays.put(field, node);
    }

    for (String field : optionalFields) {
      JsonNode node = root.get(field);
      if (node == null || node.isNull() || !node.isArray()) {
        continue; // absent optional column — Row.dblOrNull will yield null for every row
      }
      if (expected == -1) {
        expected = node.size();
      } else if (node.size() != expected) {
        throw new JsonMappingException(
            p,
            "mismatched lengths: optional "
                + field
                + "="
                + node.size()
                + " vs expected="
                + expected);
      }
      arrays.put(field, node);
    }

    int rowCount = Math.max(expected, 0);
    List<T> rows = new ArrayList<>(rowCount);
    for (int i = 0; i < rowCount; i++) {
      rows.add(rowBuilder.build(new IndexedRow(arrays, i)));
    }
    return rows;
  }

  /**
   * Builds one row from the {@code Row} accessor at a fixed index. Allowed to throw {@link
   * IOException} so {@link Row} accessors can surface {@link JsonMappingException} for strict cell
   * validation.
   */
  @FunctionalInterface
  interface RowBuilder<T> {
    T build(Row row) throws IOException;
  }

  /**
   * Build a {@link JsonDeserializer} for a parallel-arrays response: parses the tree, zips the
   * columns into rows, then wraps the resulting list in the container record. Lets each
   * response-shape declaration be a single call instead of a hand-written deserializer class — the
   * ~30-line boilerplate (extend {@code JsonDeserializer}, read tree, call {@code zip}, build
   * record) collapses to the three pieces that actually differ per endpoint: column names, per-row
   * constructor, container wrapper.
   *
   * <p>{@code wrapper} is typically the record constructor reference (e.g. {@code ApiStatus::new}).
   * Receives an immutable list — the record's compact constructor can {@code List.copyOf} for
   * defensive copy without surprises about mutability.
   *
   * @param fields names of the parallel arrays expected under the response root, in the order the
   *     {@link RowBuilder} will reference them
   * @param rowBuilder how to materialize one element of the resulting list from a {@link Row}
   * @param wrapper how to wrap the resulting list of rows in the container response record
   * @param <ROW> per-row element type produced by {@code rowBuilder}
   * @param <T> container response type
   */
  static <ROW, T> JsonDeserializer<T> listDeserializer(
      List<String> fields, RowBuilder<ROW> rowBuilder, Function<List<ROW>, T> wrapper) {
    return listDeserializer(fields, List.of(), rowBuilder, wrapper);
  }

  /**
   * Same as {@link #listDeserializer(List, RowBuilder, Function)} but with a set of optional
   * columns (see {@link #zip(JsonParser, JsonNode, List, List, RowBuilder)}). Use when the wire
   * schema has a column that some feeds omit — the row builder reads it through {@link
   * Row#dblOrNull}.
   */
  static <ROW, T> JsonDeserializer<T> listDeserializer(
      List<String> fields,
      List<String> optionalFields,
      RowBuilder<ROW> rowBuilder,
      Function<List<ROW>, T> wrapper) {
    return new JsonDeserializer<>() {
      @Override
      public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode root = p.readValueAsTree();
        List<ROW> rows = zip(p, root, fields, optionalFields, rowBuilder);
        return wrapper.apply(rows);
      }
    };
  }

  /**
   * Strict typed accessors over one row of the parallel arrays. Each accessor verifies that the
   * cell is present and is of the expected JSON type; otherwise a {@link JsonMappingException} is
   * raised so the row never silently degrades to a sentinel value.
   */
  interface Row {
    /**
     * @throws JsonMappingException if the cell is null, missing, or not a JSON string.
     */
    String text(String field) throws JsonMappingException;

    /**
     * @throws JsonMappingException if the cell is null, missing, or not a JSON boolean.
     */
    boolean bool(String field) throws JsonMappingException;

    /**
     * @throws JsonMappingException if the cell is null, missing, or not a JSON number.
     */
    double dbl(String field) throws JsonMappingException;

    /**
     * @throws JsonMappingException if the cell is null, missing, or not a JSON number.
     */
    long lng(String field) throws JsonMappingException;

    /**
     * Lenient numeric accessor for <em>optional</em> columns: returns {@code null} when the column
     * was absent from the response (i.e. declared via the optional-fields list and not present) or
     * the cell itself is null/missing. A present-but-wrong-type cell still raises {@link
     * JsonMappingException} — leniency covers absence, not corruption.
     */
    @Nullable Double dblOrNull(String field) throws JsonMappingException;

    /** Lenient string accessor: {@code null} when the column is absent or the cell is null. */
    @Nullable String textOrNull(String field) throws JsonMappingException;

    /** Lenient long accessor: {@code null} when the column is absent or the cell is null. */
    @Nullable Long lngOrNull(String field) throws JsonMappingException;

    /** Lenient boolean accessor: {@code null} when the column is absent or the cell is null. */
    @Nullable Boolean boolOrNull(String field) throws JsonMappingException;

    /**
     * Raw access for custom conversions (e.g. nested objects or non-trivial date parsing). Returns
     * the node verbatim — the caller decides how to validate.
     */
    JsonNode node(String field);

    /** Like {@link #node} but {@code null} when the column is absent or the cell is null. */
    @Nullable JsonNode nodeOrNull(String field);
  }

  private static final class IndexedRow implements Row {
    private final Map<String, JsonNode> arrays;
    private final int index;

    IndexedRow(Map<String, JsonNode> arrays, int index) {
      this.arrays = arrays;
      this.index = index;
    }

    @Override
    public String text(String field) throws JsonMappingException {
      JsonNode cell = requirePresent(field);
      if (!cell.isTextual()) {
        throw typeMismatch(field, "string", cell);
      }
      return cell.asText();
    }

    @Override
    public boolean bool(String field) throws JsonMappingException {
      JsonNode cell = requirePresent(field);
      if (!cell.isBoolean()) {
        throw typeMismatch(field, "boolean", cell);
      }
      return cell.asBoolean();
    }

    @Override
    public double dbl(String field) throws JsonMappingException {
      JsonNode cell = requirePresent(field);
      if (!cell.isNumber()) {
        throw typeMismatch(field, "number", cell);
      }
      return cell.asDouble();
    }

    @Override
    public long lng(String field) throws JsonMappingException {
      JsonNode cell = requirePresent(field);
      if (!cell.isNumber()) {
        throw typeMismatch(field, "number", cell);
      }
      return cell.asLong();
    }

    @Override
    public @Nullable Double dblOrNull(String field) throws JsonMappingException {
      if (!arrays.containsKey(field)) {
        return null; // optional column absent from the response entirely
      }
      JsonNode cell = arrays.get(field).get(index);
      if (cell == null || cell.isNull() || cell.isMissingNode()) {
        return null;
      }
      if (!cell.isNumber()) {
        throw typeMismatch(field, "number", cell);
      }
      return cell.asDouble();
    }

    @Override
    public @Nullable String textOrNull(String field) throws JsonMappingException {
      JsonNode cell = cellOrNull(field);
      if (cell == null) {
        return null;
      }
      if (!cell.isTextual()) {
        throw typeMismatch(field, "string", cell);
      }
      return cell.asText();
    }

    @Override
    public @Nullable Long lngOrNull(String field) throws JsonMappingException {
      JsonNode cell = cellOrNull(field);
      if (cell == null) {
        return null;
      }
      if (!cell.isNumber()) {
        throw typeMismatch(field, "number", cell);
      }
      return cell.asLong();
    }

    @Override
    public @Nullable Boolean boolOrNull(String field) throws JsonMappingException {
      JsonNode cell = cellOrNull(field);
      if (cell == null) {
        return null;
      }
      if (!cell.isBoolean()) {
        throw typeMismatch(field, "boolean", cell);
      }
      return cell.asBoolean();
    }

    @Override
    public JsonNode node(String field) {
      return cell(field);
    }

    @Override
    public @Nullable JsonNode nodeOrNull(String field) {
      return cellOrNull(field);
    }

    /**
     * The cell for {@code field}, or {@code null} when the column is absent or the cell is null.
     */
    private @Nullable JsonNode cellOrNull(String field) {
      if (!arrays.containsKey(field)) {
        return null;
      }
      JsonNode cell = arrays.get(field).get(index);
      if (cell == null || cell.isNull() || cell.isMissingNode()) {
        return null;
      }
      return cell;
    }

    private JsonNode cell(String field) {
      JsonNode array = arrays.get(field);
      if (array == null) {
        throw new IllegalArgumentException(
            "Row accessor asked for unknown field '"
                + field
                + "'; declared columns are "
                + arrays.keySet());
      }
      return array.get(index);
    }

    private JsonNode requirePresent(String field) throws JsonMappingException {
      JsonNode cell = cell(field);
      if (cell == null || cell.isNull() || cell.isMissingNode()) {
        throw new JsonMappingException(null, "null cell at field '" + field + "' row " + index);
      }
      return cell;
    }

    private JsonMappingException typeMismatch(String field, String expected, JsonNode actual) {
      return new JsonMappingException(
          null,
          "expected "
              + expected
              + " at field '"
              + field
              + "' row "
              + index
              + " but got "
              + actual.getNodeType());
    }
  }
}
