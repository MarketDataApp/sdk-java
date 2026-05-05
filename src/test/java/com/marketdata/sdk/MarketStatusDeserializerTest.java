package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.markets.MarketStatus;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MarketStatusDeserializerTest {

  private final ObjectMapper mapper = newMapper();

  private static ObjectMapper newMapper() {
    // Per ADR-007 response records carry no @JsonDeserialize annotation — the deserializer
    // is registered programmatically (HttpTransport does this in production; the test mirrors
    // the same wiring so it exercises the real deserializer).
    ObjectMapper m = new ObjectMapper();
    SimpleModule module = new SimpleModule("marketdata-wire-test");
    module.addDeserializer(MarketStatus.class, new MarketStatusDeserializer());
    m.registerModule(module);
    return m;
  }

  @Test
  void parsesOkResponseIntoChronologicalDays() throws IOException {
    // 1706745600 = 2024-02-01 00:00:00 UTC = 2024-01-31 19:00 US/Eastern → date 2024-01-31
    // The API normalizes "trading day midnight Eastern" to a unix timestamp; we expect the
    // deserializer to recover the local Eastern date.
    String json =
        """
        { "s": "ok",
          "date":   [1706673600, 1706760000, 1706846400],
          "status": ["open", "open", "closed"] }
        """;

    MarketStatus status = mapper.readValue(json, MarketStatus.class);

    assertThat(status.days()).hasSize(3);
    assertThat(status.days().get(0).open()).isTrue();
    assertThat(status.days().get(1).open()).isTrue();
    assertThat(status.days().get(2).open()).isFalse();
    assertThat(status.days().get(0).date()).isInstanceOf(LocalDate.class);
    assertThat(status.isEmpty()).isFalse();
  }

  @Test
  void noDataResponseProducesEmptyResult() throws IOException {
    MarketStatus status = mapper.readValue("{\"s\":\"no_data\"}", MarketStatus.class);

    assertThat(status.days()).isEmpty();
    assertThat(status.isEmpty()).isTrue();
  }

  @Test
  void rejectsUnknownStatusField() {
    assertThatThrownBy(() -> mapper.readValue("{\"s\":\"weird\"}", MarketStatus.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("'weird'");
  }

  @Test
  void rejectsMismatchedArraySizes() {
    String json =
        """
        { "s": "ok",
          "date":   [1706673600, 1706760000],
          "status": ["open"] }
        """;

    assertThatThrownBy(() -> mapper.readValue(json, MarketStatus.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("different sizes");
  }

  @Test
  void rejectsResponseMissingArrays() {
    String json = "{\"s\":\"ok\"}";

    assertThatThrownBy(() -> mapper.readValue(json, MarketStatus.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected 'date' and 'status' arrays");
  }
}
