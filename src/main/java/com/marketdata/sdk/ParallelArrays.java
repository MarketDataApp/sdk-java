package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  private ParallelArrays() {}

  /**
   * Zip the parallel arrays under {@code root} into a list of rows via {@code rowBuilder}.
   *
   * @throws JsonMappingException if the envelope reports {@code "s":"error"}, a required field is
   *     absent or not an array, or arrays have mismatched lengths.
   */
  static <T> List<T> zip(JsonParser p, JsonNode root, List<String> fields, RowBuilder<T> rowBuilder)
      throws IOException {

    String envelopeStatus = root.path(ENVELOPE_STATUS).asText("");
    if ("error".equals(envelopeStatus)) {
      String errmsg = root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)");
      throw new JsonMappingException(p, "API responded with error: " + errmsg);
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
     * Raw access for custom conversions (e.g. nested objects or non-trivial date parsing). Returns
     * the node verbatim — the caller decides how to validate.
     */
    JsonNode node(String field);
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
    public JsonNode node(String field) {
      return cell(field);
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
