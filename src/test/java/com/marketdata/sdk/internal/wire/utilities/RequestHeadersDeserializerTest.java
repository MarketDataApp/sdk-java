package com.marketdata.sdk.internal.wire.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.sdk.utilities.RequestHeaders;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RequestHeadersDeserializerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void parsesArbitraryHeadersAndLowercasesKeys() throws IOException {
    String json =
        """
        {
          "accept": "*/*",
          "Authorization": "Bearer ***YKT0",
          "X-Real-IP": "127.0.0.1",
          "User-Agent": "marketdata-sdk-java/0.1.0"
        }
        """;

    RequestHeaders result = mapper.readValue(json, RequestHeaders.class);

    assertThat(result.all()).hasSize(4);
    // case-insensitive lookup roundtrips:
    assertThat(result.get("Authorization")).hasValue("Bearer ***YKT0");
    assertThat(result.get("authorization")).hasValue("Bearer ***YKT0");
    assertThat(result.get("AUTHORIZATION")).hasValue("Bearer ***YKT0");
    assertThat(result.get("user-agent")).hasValue("marketdata-sdk-java/0.1.0");
  }

  @Test
  void emptyObjectProducesEmptyResult() throws IOException {
    RequestHeaders result = mapper.readValue("{}", RequestHeaders.class);
    assertThat(result.isEmpty()).isTrue();
    assertThat(result.get("anything")).isEmpty();
  }

  @Test
  void nonObjectResponseProducesParseException() {
    assertThatThrownBy(() -> mapper.readValue("[]", RequestHeaders.class))
        .isInstanceOf(JsonMappingException.class)
        .hasMessageContaining("expected a JSON object");
  }
}
