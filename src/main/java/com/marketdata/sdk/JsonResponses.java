package com.marketdata.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shared execution path for the typed (JSON) resource endpoints: dispatch the spec through the
 * transport, decode the body with the carried {@code requestedColumns} (§3 Option A), and wrap the
 * result via a {@link ResponseFactory}. This is the typed twin of {@link TextResponses} — the only
 * thing that varied across the five typed resources was the decode type and the factory, so the
 * orchestration lives here once instead of being copied per resource.
 *
 * <p>Resources with no universal-param config (e.g. {@code utilities}) pass {@link List#of()} for
 * {@code requestedColumns}, which the parser treats as "all columns" — identical to its no-columns
 * {@code parse} overload.
 */
final class JsonResponses {

  private JsonResponses() {}

  static <D, R> CompletableFuture<R> execute(
      HttpTransport transport,
      JsonResponseParser parser,
      RequestSpec spec,
      List<String> requestedColumns,
      Class<D> decodeType,
      ResponseFactory<D, R> factory) {
    return transport
        .executeAsync(spec)
        .thenApply(
            env ->
                factory.create(
                    parser.parse(env, decodeType, requestedColumns), env, spec.format()));
  }

  static <D, R> CompletableFuture<R> execute(
      HttpTransport transport,
      JsonResponseParser parser,
      RequestSpec spec,
      RetryPolicy policy,
      List<String> requestedColumns,
      Class<D> decodeType,
      ResponseFactory<D, R> factory) {
    return transport
        .executeAsync(spec, policy)
        .thenApply(
            env ->
                factory.create(
                    parser.parse(env, decodeType, requestedColumns), env, spec.format()));
  }
}
