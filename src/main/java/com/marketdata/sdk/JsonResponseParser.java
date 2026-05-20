package com.marketdata.sdk;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.ParseError;
import java.io.IOException;
import java.time.Clock;

/**
 * Decodes {@link HttpResponseEnvelope} bodies into typed records.
 *
 * <p>Owns one {@link ObjectMapper} per {@link MarketDataClient} (Jackson mappers are thread-safe
 * and expensive to construct, so we build and reuse). Per ADR-007, wire-format deserializers are
 * registered programmatically — response records never carry {@code @JsonDeserialize} annotations.
 * The parser itself is <strong>resource-agnostic</strong>: it does not know about {@code User},
 * {@code ApiStatus}, or any other domain type. Each {@code *Resource} self-registers its
 * deserializers in its constructor via {@link #registerModule(Module)}, so adding a new resource
 * does not require editing this file. Registration must happen before the first {@link #parse}
 * call, which is satisfied today because resources are constructed at {@code MarketDataClient}
 * construction time, before any HTTP traffic.
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
    this.mapper = new ObjectMapper();
    this.clock = clock;
  }

  /**
   * Attach a Jackson {@link Module} (typically a {@code SimpleModule} populated with one resource's
   * deserializers). Resources call this from their constructor to wire their wire-format mappings
   * without coupling the parser to their domain types. Idempotent for modules sharing the same
   * type-id (Jackson skips duplicates).
   */
  void registerModule(Module module) {
    mapper.registerModule(module);
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
