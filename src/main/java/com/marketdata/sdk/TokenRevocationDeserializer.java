package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.TokenRevocation;
import java.io.IOException;

/**
 * Wire-format deserializer for {@link TokenRevocation}. {@code DELETE /v1/user/token/} returns the
 * same flat success/error envelope as {@code options/lookup} ({@code {"s":"ok"}} or {@code
 * {"s":"error","errmsg":"..."}}), with no further fields on success.
 */
final class TokenRevocationDeserializer extends JsonDeserializer<TokenRevocation> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_ERROR = "error";

  @Override
  public TokenRevocation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    if (ENVELOPE_ERROR.equals(root.path(ENVELOPE_STATUS).asText(""))) {
      throw new JsonMappingException(
          p, "API responded with error: " + root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)"));
    }
    return new TokenRevocation();
  }
}
