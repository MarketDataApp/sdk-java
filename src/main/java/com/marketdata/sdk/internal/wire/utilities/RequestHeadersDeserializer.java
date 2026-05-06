package com.marketdata.sdk.internal.wire.utilities;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.RequestHeaders;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Jackson deserializer for {@code GET /headers/}. The response is a flat JSON object with arbitrary
 * header names as keys — there's no fixed schema, so a record with named fields would not fit.
 * Instead we collapse every top-level key/value pair into a single {@code Map} and let {@link
 * RequestHeaders} expose case-insensitive lookups on top of it.
 *
 * <p>Keys are lower-cased here so {@link RequestHeaders#get} can do its case-insensitive lookup
 * with a simple HashMap probe.
 */
public final class RequestHeadersDeserializer extends JsonDeserializer<RequestHeaders> {

  @Override
  public RequestHeaders deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    if (!root.isObject()) {
      throw new IOException(
          "Malformed /headers response: expected a JSON object, got " + root.getNodeType());
    }
    Map<String, String> headers = new LinkedHashMap<>();
    root.fields()
        .forEachRemaining(
            entry ->
                headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().asText()));
    return new RequestHeaders(Map.copyOf(headers));
  }
}
