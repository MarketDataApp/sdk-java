package com.marketdata.sdk;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.ParseError;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

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
  /** Attribute key under which the requested {@code columns} (§3) travel into deserializers. */
  static final String REQUESTED_COLUMNS_ATTR = "marketdata.requestedColumns";

  <T> T parse(HttpResponseEnvelope env, Class<T> type) {
    return parse(env, type, List.of());
  }

  /**
   * Decode like {@link #parse(HttpResponseEnvelope, Class)} but additionally make the consumer's
   * requested {@code columns} available to deserializers (via a Jackson context attribute) so they
   * can enforce Option A: a required column that was requested but the API omitted surfaces as a
   * {@link ParseError}, never a silent null. {@code requestedColumns} empty means "all columns".
   */
  <T> T parse(HttpResponseEnvelope env, Class<T> type, List<String> requestedColumns) {
    // Issue #29: a zero-length body surfaces from Jackson as a generic "No content to map"
    // MismatchedInputException — diagnostically thin, often confusing in the presence of a
    // body-stripping proxy. Pre-check so the failure carries a precise, actionable message that
    // names the actual symptom ("empty response body") instead of looking like a corruption.
    if (env.body().length == 0) {
      ErrorContext context =
          ErrorContext.forResponse(
              env.url().toString(), env.statusCode(), env.requestId(), clock.instant());
      throw new ParseError(
          "Empty response body from "
              + HttpDispatcher.safeUri(env.url())
              + " — server returned 0 bytes (a proxy may have stripped the payload, or the"
              + " endpoint replied without one)",
          context);
    }
    try {
      return mapper
          .readerFor(type)
          .withAttribute(REQUESTED_COLUMNS_ATTR, requestedColumns)
          .readValue(env.body());
    } catch (IOException e) {
      ErrorContext context =
          ErrorContext.forResponse(
              env.url().toString(), env.statusCode(), env.requestId(), clock.instant());
      // §16: getMessage() is consumer-accessible and routinely logged. Strip query strings so
      // tokens/account_ids/symbols never persist through this surface. The full URI remains
      // available on the ErrorContext for callers with the right discretion.
      throw new ParseError(
          "Failed to decode response from "
              + HttpDispatcher.safeUri(env.url())
              + ": "
              + e.getMessage(),
          context,
          e);
    }
  }
}
