package com.marketdata.sdk;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Shared single-request execution path for the text-format facets (CSV and HTML). Both apply the
 * carried {@link RequestConfig} onto the builder, force the output {@link Format}, dispatch through
 * the transport, and wrap the UTF-8 decoded body in a format-specific response. The only things
 * that differ are the format flag and the response constructor, so this lives here once instead of
 * being copied across the eight facet classes.
 *
 * <p>Multi-request paths that need custom merging (e.g. {@code stocks.candles} year-chunking, the
 * {@code options.quotes} fan-out map) keep their own dispatch loop — only the simple one-request
 * case is shared here.
 */
final class TextResponses {

  private TextResponses() {}

  /** Builds a text response from the decoded body, the response envelope, and the sent format. */
  @FunctionalInterface
  interface Factory<R> {
    R create(String body, HttpResponseEnvelope envelope, Format format);
  }

  static <R> CompletableFuture<R> execute(
      HttpTransport transport,
      RequestConfig config,
      RequestSpec.Builder builder,
      Format format,
      Factory<R> factory) {
    config.applyTo(builder);
    builder.format(format);
    RequestSpec spec = builder.build();
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                factory.create(new String(env.body(), StandardCharsets.UTF_8), env, spec.format()));
  }
}
