package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.marketdata.sdk.utilities.RequestHeaders;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire-format deserializer for {@link RequestHeaders}. The server returns a flat JSON object —
 * {@code {"accept":"*\/*","cf-ray":"abc-123",...}} — and we wrap it in a {@code RequestHeaders}
 * record at the Jackson layer rather than via an annotation on the record (per ADR-007: response
 * records don't carry {@code @JsonDeserialize}; deserializers register programmatically on the
 * parser's {@code ObjectMapper}).
 *
 * <p>A literal JSON {@code null} body — or any other path that would leave the parser holding a
 * {@code null} map — is short-circuited to a {@link JsonMappingException} so {@link
 * JsonResponseParser} surfaces it as a {@link com.marketdata.sdk.exception.ParseError} with the
 * request's URL/status/id attached. Without the guard, Jackson would return {@code null} from the
 * top-level {@code readValue} (via {@link #getNullValue}, its standard null-routing seam) and the
 * NPE would surface much later — uncaught by the parser's {@code catch (IOException)} and far less
 * useful to a consumer trying to diagnose a malformed response.
 */
final class RequestHeadersDeserializer extends JsonDeserializer<RequestHeaders> {

  private static final TypeReference<Map<String, String>> MAP_OF_STRINGS = new TypeReference<>() {};

  @Override
  public RequestHeaders deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    return buildHeaders(p, p.readValueAs(MAP_OF_STRINGS));
  }

  /**
   * Wrap the decoded header map. {@code @Generated}: the null-map guard is unreachable — a
   * top-level JSON null is routed through {@link #getNullValue} before {@code deserialize()} runs,
   * so the guard is defense-in-depth no hermetic test can provoke.
   */
  @Generated
  private static RequestHeaders buildHeaders(JsonParser p, @Nullable Map<String, String> raw)
      throws JsonMappingException {
    if (raw == null) {
      throw JsonMappingException.from(p, "expected a JSON object for /headers/ body, got null map");
    }
    return new RequestHeaders(raw);
  }

  /**
   * Jackson routes a top-level JSON {@code null} through this seam instead of calling {@link
   * #deserialize}. Default behavior returns {@code null}; we instead throw so the wire-null case
   * produces a {@link com.marketdata.sdk.exception.ParseError} with the endpoint URL in scope,
   * matching the failure shape of any other malformed body.
   */
  @Override
  public RequestHeaders getNullValue(DeserializationContext ctxt) throws JsonMappingException {
    throw JsonMappingException.from(
        ctxt, "expected a JSON object for /headers/ body, got JSON null");
  }
}
