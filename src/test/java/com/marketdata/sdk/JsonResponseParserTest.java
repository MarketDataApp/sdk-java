package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.utilities.RequestHeaders;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonResponseParserTest {

  private static HttpResponseEnvelope env(String body) {
    return new HttpResponseEnvelope(
        body.getBytes(),
        200,
        "test-request-id",
        HttpHeaders.of(Map.of(), (a, b) -> true),
        URI.create("http://localhost/headers/"));
  }

  @Test
  void parsesRequestHeadersFromFlatJsonObject() {
    JsonResponseParser parser = new JsonResponseParser();

    RequestHeaders rh =
        parser.parse(
            env("{\"accept\":\"*/*\",\"cf-ray\":\"abc-123\",\"user-agent\":\"java/0\"}"),
            RequestHeaders.class);

    assertThat(rh.headers())
        .containsEntry("accept", "*/*")
        .containsEntry("cf-ray", "abc-123")
        .containsEntry("user-agent", "java/0");
  }

  @Test
  void emptyJsonObjectProducesEmptyHeaders() {
    JsonResponseParser parser = new JsonResponseParser();
    RequestHeaders rh = parser.parse(env("{}"), RequestHeaders.class);
    assertThat(rh.headers()).isEmpty();
  }

  @Test
  void requestHeadersMapIsImmutable() {
    JsonResponseParser parser = new JsonResponseParser();
    RequestHeaders rh = parser.parse(env("{\"a\":\"1\"}"), RequestHeaders.class);

    assertThatThrownBy(() -> rh.headers().put("hacked", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void malformedJsonRaisesParseErrorCarryingResponseContext() {
    JsonResponseParser parser = new JsonResponseParser();

    assertThatThrownBy(() -> parser.parse(env("{not-json"), RequestHeaders.class))
        .isInstanceOf(ParseError.class)
        .satisfies(
            t -> {
              ParseError err = (ParseError) t;
              assertThat(err.getRequestUrl()).isEqualTo("http://localhost/headers/");
              assertThat(err.getStatusCode()).isEqualTo(200);
              assertThat(err.getRequestId()).isEqualTo("test-request-id");
              assertThat(err.getCause()).isNotNull();
            });
  }
}
