package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.NetworkError;
import com.marketdata.sdk.options.OptionsExpirations;
import com.marketdata.sdk.stocks.StockNews;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Error-path coverage for deserializers and small utilities, exercised directly. */
class ParserAndUtilCoverageTest {

  private static <T> ObjectMapper mapperFor(
      Class<T> type, com.fasterxml.jackson.databind.JsonDeserializer<T> deser) {
    ObjectMapper m = new ObjectMapper();
    SimpleModule module = new SimpleModule("test");
    module.addDeserializer(type, deser);
    m.registerModule(module);
    return m;
  }

  @Test
  void stockNewsDeserializerSurfacesErrorEnvelope() {
    ObjectMapper m = mapperFor(StockNews.class, new StockNewsDeserializer());

    assertThatThrownBy(
            () ->
                m.readValue("{\"s\":\"error\",\"errmsg\":\"news service down\"}", StockNews.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("news service down");
  }

  @Test
  void optionsExpirationsDeserializerRejectsMissingArray() {
    ObjectMapper m = mapperFor(OptionsExpirations.class, new OptionsExpirationsDeserializer());

    assertThatThrownBy(() -> m.readValue("{\"s\":\"ok\"}", OptionsExpirations.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("missing or non-array");
  }

  @Test
  void mergeCsvBodiesSkipsEmptySlices() {
    // i>0 with headers on: a slice that is empty after the header row is dropped must be skipped,
    // not appended as a stray blank line.
    String merged = StocksCsvResource.mergeCsvBodies(List.of("t,c\n1,2", "", "t,c\n3,4"), true);

    assertThat(merged).isEqualTo("t,c\n1,2\n3,4");
  }

  @Test
  void retryPolicyBailsOnSelfReferentialCauseChain() {
    RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(1));
    ErrorContext ctx = ErrorContext.forNoResponse("https://api.example", Instant.EPOCH);
    // A throwable whose getCause() returns itself — the cause-chain walk must bail, not spin.
    Throwable selfCycle =
        new RuntimeException("loop") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };
    NetworkError net = new NetworkError("network", ctx, selfCycle);

    // No IOException reachable in the (self-cycling) chain → not retriable.
    assertThat(policy.shouldRetry(net, 0)).isFalse();
  }

  @Test
  void parallelArraysSkipsNonArrayOptionalColumn() throws Exception {
    // An optional column present but not an array (e.g. a scalar) is skipped via `continue`, the
    // same as an absent one — Row.dblOrNull then yields null for every row.
    com.fasterxml.jackson.databind.JsonNode root =
        new ObjectMapper().readTree("{\"s\":\"ok\",\"a\":[\"x\"],\"opt\":\"scalar\"}");

    List<Double> rows =
        ParallelArrays.zip(null, root, List.of("a"), List.of("opt"), r -> r.dblOrNull("opt"));

    assertThat(rows).containsExactly((Double) null);
  }
}
