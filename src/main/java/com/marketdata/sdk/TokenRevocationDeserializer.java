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
 * {"s":"error","errmsg":"..."}}), with no further fields on success. Only an explicit {@code s=ok}
 * decodes to success; anything else — a missing {@code s} field, an unrecognized status, or {@code
 * s=error} — is rejected so a malformed or unexpected body never reads as a confirmed revocation.
 */
final class TokenRevocationDeserializer extends JsonDeserializer<TokenRevocation> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_OK = "ok";
  private static final String ENVELOPE_ERROR = "error";

  @Override
  public TokenRevocation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    String status = root.path(ENVELOPE_STATUS).asText("");
    if (ENVELOPE_OK.equals(status)) {
      return new TokenRevocation();
    }
    if (ENVELOPE_ERROR.equals(status)) {
      throw new JsonMappingException(
          p, "API responded with error: " + root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)"));
    }
    throw new JsonMappingException(
        p, "API responded with unexpected status: " + (status.isEmpty() ? "(none)" : status));
  }

  /**
   * Jackson routes a top-level JSON {@code null} through this seam instead of calling {@link
   * #deserialize}; without overriding it, a {@code null} body would decode to {@code null} rather
   * than being rejected as an unexpected status (see {@link RequestHeadersDeserializer} for the
   * same guard on {@code /headers/}).
   */
  @Override
  public TokenRevocation getNullValue(DeserializationContext ctxt) throws JsonMappingException {
    throw JsonMappingException.from(ctxt, "API responded with unexpected status: (none)");
  }
}
