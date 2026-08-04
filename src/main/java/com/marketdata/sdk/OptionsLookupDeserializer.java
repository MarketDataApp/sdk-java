package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.options.OptionsLookup;
import java.io.IOException;

/**
 * Wire-format deserializer for {@link OptionsLookup}. The {@code /options/lookup/} endpoint is the
 * one options endpoint that does not use the parallel-arrays envelope — the body is a flat object
 * ({@code {"s":"ok","optionSymbol":"AAPL250117C00150000"}}). Envelope handling mirrors {@link
 * ParallelArrays} so consumers see consistent behavior across resources:
 *
 * <ul>
 *   <li>{@code "s":"error"} → {@link JsonMappingException} carrying the server's {@code errmsg}.
 *   <li>missing or non-textual {@code optionSymbol} → {@link JsonMappingException} (strict by
 *       default, same reasoning as {@link UserDeserializer}).
 * </ul>
 */
final class OptionsLookupDeserializer extends JsonDeserializer<OptionsLookup> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_ERROR = "error";
  private static final String OPTION_SYMBOL_KEY = "optionSymbol";

  @Override
  public OptionsLookup deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    if (ENVELOPE_ERROR.equals(root.path(ENVELOPE_STATUS).asText(""))) {
      throw new JsonMappingException(
          p,
          "API responded with error: "
              + LogSafe.sanitize(root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)")));
    }
    JsonNode node = root.get(OPTION_SYMBOL_KEY);
    if (node == null || !node.isTextual()) {
      throw new JsonMappingException(p, "missing or non-string field: " + OPTION_SYMBOL_KEY);
    }
    return new OptionsLookup(node.asText());
  }
}
