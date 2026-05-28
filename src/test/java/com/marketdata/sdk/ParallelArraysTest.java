package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelArraysTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode parse(String json) throws IOException {
    return MAPPER.readTree(json);
  }

  // ---------- happy path: zip + typed accessors ----------

  @Test
  void zipsParallelArraysIntoRowsViaTypedAccessors() throws IOException {
    JsonNode root =
        parse(
            "{\"s\":\"ok\","
                + "\"symbol\":[\"AAPL\",\"MSFT\"],"
                + "\"price\":[150.0,400.0],"
                + "\"active\":[true,false],"
                + "\"updated\":[1700000000,1700000001]}");

    List<Record> rows =
        ParallelArrays.zip(
            null,
            root,
            List.of("symbol", "price", "active", "updated"),
            row ->
                new Record(
                    row.text("symbol"), row.dbl("price"), row.bool("active"), row.lng("updated")));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).isEqualTo(new Record("AAPL", 150.0, true, 1700000000L));
    assertThat(rows.get(1)).isEqualTo(new Record("MSFT", 400.0, false, 1700000001L));
  }

  @Test
  void emptyArraysProduceEmptyListWithoutInvokingBuilder() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"a\":[],\"b\":[]}");

    List<String> rows =
        ParallelArrays.zip(
            null,
            root,
            List.of("a", "b"),
            row -> {
              throw new AssertionError("builder must not be invoked when arrays are empty");
            });

    assertThat(rows).isEmpty();
  }

  // ---------- envelope-error short-circuit ----------

  @Test
  void serverSideErrorEnvelopeShortCircuitsBeforeFieldValidation() {
    // s=error means the body intentionally omits the data arrays. The helper must surface the
    // errmsg instead of complaining about missing fields downstream.
    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null,
                    parse("{\"s\":\"error\",\"errmsg\":\"database connection refused\"}"),
                    List.of("symbol"),
                    row -> null))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("database connection refused");
  }

  @Test
  void errorEnvelopeWithoutErrmsgYieldsPlaceholderText() {
    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null, parse("{\"s\":\"error\"}"), List.of("symbol"), row -> null))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("no errmsg field");
  }

  // ---------- no_data envelope (paired with HTTP 404) ----------

  @Test
  void noDataEnvelopeShortCircuitsToEmptyListWithoutFieldValidation() throws IOException {
    // The backend returns {"s":"no_data"} (HTTP 404) when a query has no results — the data
    // arrays are deliberately absent. The helper must return an empty list so the deserializer
    // wraps it in its container type instead of complaining about "missing field".
    List<String> rows =
        ParallelArrays.zip(
            null,
            parse("{\"s\":\"no_data\"}"),
            List.of("symbol", "price"),
            row -> {
              throw new AssertionError("builder must not be invoked for no_data envelope");
            });

    assertThat(rows).isEmpty();
  }

  @Test
  void noDataEnvelopeIgnoresAdjacentMetadataFields() throws IOException {
    // Some backend handlers attach metadata to the no_data envelope (e.g. nextTime, prevTime,
    // errmsg). Those fields are not the parallel-array columns and must not affect the result.
    List<String> rows =
        ParallelArrays.zip(
            null,
            parse(
                "{\"s\":\"no_data\","
                    + "\"nextTime\":null,"
                    + "\"prevTime\":null,"
                    + "\"errmsg\":\"Market closed on this date.\"}"),
            List.of("symbol", "price"),
            row -> {
              throw new AssertionError("builder must not be invoked for no_data envelope");
            });

    assertThat(rows).isEmpty();
  }

  // ---------- presence and length validation ----------

  @Test
  void missingFieldFailsWithFieldName() {
    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null,
                    parse("{\"s\":\"ok\",\"symbol\":[\"AAPL\"]}"),
                    List.of("symbol", "price"),
                    row -> null))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("missing or non-array")
        .hasMessageContaining("price");
  }

  @Test
  void nonArrayFieldFailsWithFieldName() {
    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null,
                    parse("{\"s\":\"ok\",\"symbol\":\"AAPL\",\"price\":[150.0]}"),
                    List.of("symbol", "price"),
                    row -> null))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("missing or non-array")
        .hasMessageContaining("symbol");
  }

  @Test
  void mismatchedLengthsFailWithDetail() {
    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null,
                    parse(
                        "{\"s\":\"ok\","
                            + "\"symbol\":[\"AAPL\",\"MSFT\"],"
                            + "\"price\":[150.0]}"),
                    List.of("symbol", "price"),
                    row -> null))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("mismatched lengths")
        .hasMessageContaining("price=1")
        .hasMessageContaining("expected=2");
  }

  // ---------- Row.node() for custom conversions ----------

  @Test
  void rowNodeExposesRawJsonNodeForCustomConversion() throws IOException {
    // node() lets the builder do conversions the typed helpers don't cover — here we parse
    // an array element directly so the test exercises that escape hatch.
    JsonNode root = parse("{\"s\":\"ok\",\"nested\":[{\"k\":\"v1\"},{\"k\":\"v2\"}]}");

    List<String> rows =
        ParallelArrays.zip(
            null, root, List.of("nested"), row -> row.node("nested").get("k").asText());

    assertThat(rows).containsExactly("v1", "v2");
  }

  // ---------- Row programming errors ----------

  @Test
  void rowAccessorRejectsUndeclaredField() throws IOException {
    // Asking for a field that wasn't in the declared `fields` list is a programming bug in the
    // builder lambda — surface it loudly rather than NPEing on a null array.
    JsonNode root = parse("{\"s\":\"ok\",\"a\":[\"x\"]}");

    assertThatThrownBy(
            () ->
                ParallelArrays.zip(
                    null,
                    root,
                    List.of("a"),
                    row -> row.text("nonexistent"))) // builder asks for an undeclared column
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent")
        .hasMessageContaining("[a]");
  }

  // ---------- strict cell validation ----------

  @Test
  void textFailsWhenCellIsJsonNull() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"symbol\":[\"AAPL\",null]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("symbol"), row -> row.text("symbol")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("null cell")
        .hasMessageContaining("symbol")
        .hasMessageContaining("row 1");
  }

  @Test
  void textFailsWhenCellIsNotAString() throws IOException {
    // The server suddenly sending a number where a symbol is expected is the kind of regression
    // we want to surface immediately, not silently coerce to "123" via Jackson's lax asText.
    JsonNode root = parse("{\"s\":\"ok\",\"symbol\":[123]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("symbol"), row -> row.text("symbol")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected string")
        .hasMessageContaining("symbol");
  }

  @Test
  void boolFailsWhenCellIsJsonNull() throws IOException {
    // The exact regression flagged in the review: a missing `online` cell silently becoming
    // `false` would mass-block retries via StatusCache. Strict mode rejects it.
    JsonNode root = parse("{\"s\":\"ok\",\"online\":[true,null]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("online"), row -> row.bool("online")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("null cell")
        .hasMessageContaining("online");
  }

  @Test
  void boolFailsWhenCellIsNotABoolean() throws IOException {
    // "true" as a string is not the same as boolean true — Jackson's lax asBoolean would coerce.
    JsonNode root = parse("{\"s\":\"ok\",\"online\":[\"true\"]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("online"), row -> row.bool("online")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected boolean")
        .hasMessageContaining("online");
  }

  @Test
  void dblFailsWhenCellIsJsonNull() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"price\":[null]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("price"), row -> row.dbl("price")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("null cell")
        .hasMessageContaining("price");
  }

  @Test
  void dblFailsWhenCellIsNotANumber() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"price\":[\"150.0\"]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("price"), row -> row.dbl("price")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected number")
        .hasMessageContaining("price");
  }

  @Test
  void lngFailsWhenCellIsJsonNull() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"updated\":[null]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("updated"), row -> row.lng("updated")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("null cell")
        .hasMessageContaining("updated");
  }

  @Test
  void lngFailsWhenCellIsNotANumber() throws IOException {
    JsonNode root = parse("{\"s\":\"ok\",\"updated\":[true]}");

    assertThatThrownBy(
            () -> ParallelArrays.zip(null, root, List.of("updated"), row -> row.lng("updated")))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected number")
        .hasMessageContaining("updated");
  }

  @Test
  void nodeAccessorReturnsNullJsonNodeVerbatimForCustomHandling() throws IOException {
    // Strict accessors fail on null; the raw `node()` escape hatch must NOT — callers that opt
    // into raw access are responsible for handling null themselves (e.g. nested object fields).
    JsonNode root = parse("{\"s\":\"ok\",\"raw\":[null]}");

    List<Boolean> rows =
        ParallelArrays.zip(null, root, List.of("raw"), row -> row.node("raw").isNull());

    assertThat(rows).containsExactly(true);
  }

  // ---------- listDeserializer factory (issue #10) ----------

  @Test
  void listDeserializerProducesJacksonDeserializerWiringTheZipPipeline() throws IOException {
    // The factory replaces hand-written JsonDeserializer subclasses for parallel-arrays endpoints
    // (issue #10). Each new endpoint declares only its fields, row builder, and wrapper — the
    // zip + tree-read + wrap plumbing is shared.
    com.fasterxml.jackson.databind.JsonDeserializer<Container> deser =
        ParallelArrays.listDeserializer(
            List.of("symbol", "price"),
            row -> new Record(row.text("symbol"), row.dbl("price"), false, 0),
            Container::new);

    // Register on a fresh ObjectMapper and round-trip a wire-shaped payload.
    com.fasterxml.jackson.databind.ObjectMapper m =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.module.SimpleModule module =
        new com.fasterxml.jackson.databind.module.SimpleModule("test");
    module.addDeserializer(Container.class, deser);
    m.registerModule(module);

    Container c =
        m.readValue(
            "{\"s\":\"ok\",\"symbol\":[\"AAPL\",\"MSFT\"],\"price\":[150.0,400.0]}",
            Container.class);

    assertThat(c.rows()).hasSize(2);
    assertThat(c.rows().get(0).symbol()).isEqualTo("AAPL");
    assertThat(c.rows().get(1).price()).isEqualTo(400.0);
  }

  @Test
  void listDeserializerHonorsEnvelopeShortCircuits() throws IOException {
    // The factory delegates structural validation to zip(): envelope errors and no_data
    // short-circuit consistently regardless of which factory call instantiated the deserializer.
    com.fasterxml.jackson.databind.JsonDeserializer<Container> deser =
        ParallelArrays.listDeserializer(
            List.of("symbol"), row -> new Record(row.text("symbol"), 0, false, 0), Container::new);

    com.fasterxml.jackson.databind.ObjectMapper m =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.module.SimpleModule module =
        new com.fasterxml.jackson.databind.module.SimpleModule("test");
    module.addDeserializer(Container.class, deser);
    m.registerModule(module);

    // no_data → empty list, wrapped in Container.
    Container empty = m.readValue("{\"s\":\"no_data\"}", Container.class);
    assertThat(empty.rows()).isEmpty();

    // error envelope → JsonMappingException bubbles up through Jackson.
    assertThatThrownBy(() -> m.readValue("{\"s\":\"error\",\"errmsg\":\"boom\"}", Container.class))
        .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
        .hasMessageContaining("boom");
  }

  // ---------- helper record ----------

  private record Record(String symbol, double price, boolean active, long updated) {}

  /**
   * Container wrapper for the {@link
   * #listDeserializerProducesJacksonDeserializerWiringTheZipPipeline} test.
   */
  private record Container(List<Record> rows) {}
}
