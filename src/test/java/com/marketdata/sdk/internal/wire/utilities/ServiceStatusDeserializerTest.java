package com.marketdata.sdk.internal.wire.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ServiceStatusDeserializerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void parsesOkResponseIntoChronologicalServices() throws IOException {
    String json =
        """
        { "s":"ok",
          "service":      ["/v1/funds/candles/", "/v1/stocks/quotes/"],
          "status":       ["online", "offline"],
          "online":       [true, false],
          "uptimePct30d": [1.0, 0.998],
          "uptimePct90d": [1.0, 0.997],
          "updated":      [1734036832, 1734036832] }
        """;

    ServiceStatus result = mapper.readValue(json, ServiceStatus.class);

    assertThat(result.services()).hasSize(2);
    assertThat(result.services().get(0).service()).isEqualTo("/v1/funds/candles/");
    assertThat(result.services().get(0).online()).isTrue();
    assertThat(result.services().get(1).status()).isEqualTo("offline");
    assertThat(result.services().get(1).online()).isFalse();
    assertThat(result.services().get(1).uptimePct30d()).isEqualTo(0.998);
    assertThat(result.allOnline()).isFalse();
  }

  @Test
  void allOnlineIsTrueOnlyWhenEveryServiceIsOnline() throws IOException {
    String json =
        """
        { "s":"ok",
          "service":      ["a", "b"],
          "status":       ["online", "online"],
          "online":       [true, true],
          "uptimePct30d": [1, 1],
          "uptimePct90d": [1, 1],
          "updated":      [1, 1] }
        """;

    assertThat(mapper.readValue(json, ServiceStatus.class).allOnline()).isTrue();
  }

  @Test
  void noDataResponseProducesEmpty() throws IOException {
    ServiceStatus result = mapper.readValue("{\"s\":\"no_data\"}", ServiceStatus.class);
    assertThat(result.isEmpty()).isTrue();
    assertThat(result.allOnline()).isFalse(); // empty is not "all online"
  }

  @Test
  void mismatchedArraySizesProduceParseException() {
    String json =
        """
        { "s":"ok",
          "service":      ["a", "b"],
          "status":       ["online"],
          "online":       [true, true],
          "uptimePct30d": [1, 1],
          "uptimePct90d": [1, 1],
          "updated":      [1, 1] }
        """;

    assertThatThrownBy(() -> mapper.readValue(json, ServiceStatus.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("don't match");
  }

  @Test
  void unknownStatusFieldProducesParseException() {
    assertThatThrownBy(() -> mapper.readValue("{\"s\":\"weird\"}", ServiceStatus.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("'weird'");
  }
}
