package com.marketdata.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.User;
import java.io.IOException;
import java.time.Clock;

/**
 * Decodes {@link HttpResponseEnvelope} bodies into typed records.
 *
 * <p>Owns one {@link ObjectMapper} per {@link MarketDataClient} (Jackson mappers are thread-safe
 * and expensive to construct, so we build and reuse). Per ADR-007, wire-format deserializers are
 * registered programmatically on a package-private {@link SimpleModule} here — response records
 * never carry {@code @JsonDeserialize} annotations.
 *
 * <p>Resources that need raw bytes (CSV, HTML) skip this class entirely and read {@link
 * HttpResponseEnvelope#body()} directly.
 */
final class JsonResponseParser {

  private final ObjectMapper mapper;
  private final Clock clock;

  JsonResponseParser() {
    this(Clock.systemUTC());
  }

  JsonResponseParser(Clock clock) {
    ObjectMapper m = new ObjectMapper();
    SimpleModule wireModule = new SimpleModule("marketdata-wire");
    wireModule.addDeserializer(RequestHeaders.class, new RequestHeadersDeserializer());
    wireModule.addDeserializer(User.class, new UserDeserializer());
    wireModule.addDeserializer(ApiStatus.class, new ApiStatusDeserializer());
    m.registerModule(wireModule);
    this.mapper = m;
    this.clock = clock;
  }

  /**
   * Decode an envelope's body into the requested type. Throws {@link ParseError} when Jackson
   * cannot read the body — the error context carries the envelope's url, status, and request id for
   * the consumer's diagnostics.
   */
  <T> T parse(HttpResponseEnvelope env, Class<T> type) {
    try {
      return mapper.readValue(env.body(), type);
    } catch (IOException e) {
      ErrorContext context =
          ErrorContext.forResponse(
              env.url().toString(), env.statusCode(), env.requestId(), clock.instant());
      throw new ParseError(
          "Failed to decode response from " + env.url() + ": " + e.getMessage(), context, e);
    }
  }
}
