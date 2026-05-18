package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.marketdata.sdk.utilities.RequestHeaders;
import java.io.IOException;
import java.util.Map;

/**
 * Wire-format deserializer for {@link RequestHeaders}. The server returns a flat JSON object —
 * {@code {"accept":"*\/*","cf-ray":"abc-123",...}} — and we wrap it in a {@code RequestHeaders}
 * record at the Jackson layer rather than via an annotation on the record (per ADR-007: response
 * records don't carry {@code @JsonDeserialize}; deserializers register programmatically on the
 * parser's {@code ObjectMapper}).
 */
final class RequestHeadersDeserializer extends JsonDeserializer<RequestHeaders> {

  private static final TypeReference<Map<String, String>> MAP_OF_STRINGS = new TypeReference<>() {};

  @Override
  public RequestHeaders deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    Map<String, String> raw = p.readValueAs(MAP_OF_STRINGS);
    return new RequestHeaders(raw);
  }
}
