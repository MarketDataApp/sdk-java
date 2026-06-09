package com.marketdata.sdk;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * System endpoints documented at {@code https://api.marketdata.app/docs/api/utilities/}. None of
 * them are versioned ({@code /v1/}); they live at the API root.
 *
 * <p>Constructed once per {@link MarketDataClient}; the consumer reaches it through {@code
 * client.utilities()}. Constructor is package-private (ADR-007) — consumers cannot instantiate.
 *
 * <p>Every endpoint returns a named {@link MarketDataResponse} whose {@link
 * MarketDataResponse#values()} is the flat payload (the service list for {@code status}, the header
 * map for {@code headers}, the {@link User} for {@code user}). These diagnostic endpoints take no
 * universal parameters and have no CSV/HTML facet.
 */
public final class UtilitiesResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;

  UtilitiesResource(HttpTransport transport, JsonResponseParser parser) {
    this.transport = transport;
    this.parser = parser;
    // §9 / ADR-007: resources own their wire-format deserializer registration. Registering here
    // (in the resource that ships the response models) keeps the parser resource-agnostic and
    // lets future resources (stocks, options, funds, markets) add their wire formats without
    // editing a central file.
    parser.registerModule(wireFormatModule());
  }

  /**
   * Build the Jackson module that maps this resource's response records ({@link RequestHeaders},
   * {@link User}, {@link ApiStatus}) to their custom deserializers. Each call returns a fresh
   * {@link SimpleModule}; tests that need the same wiring without constructing a full resource can
   * register this directly on a bare parser.
   */
  static SimpleModule wireFormatModule() {
    SimpleModule m = new SimpleModule("marketdata-utilities");
    m.addDeserializer(RequestHeaders.class, new RequestHeadersDeserializer());
    m.addDeserializer(User.class, new UserDeserializer());
    // §11 parallel-arrays decoding via the declarative factory (issue #10): no hand-written
    // JsonDeserializer subclass — just the column list, row builder, and container wrapper. The
    // pattern scales to every future parallel-arrays endpoint (stocks/candles, options/chain, …)
    // without copy-pasting the ~30-line deserializer skeleton.
    m.addDeserializer(
        ApiStatus.class,
        ParallelArrays.listDeserializer(
            List.of("service", "status", "online", "uptimePct30d", "uptimePct90d", "updated"),
            row ->
                new ServiceStatus(
                    row.text("service"),
                    row.text("status"),
                    row.bool("online"),
                    row.dbl("uptimePct30d"),
                    row.dbl("uptimePct90d"),
                    MarketDataDates.marketTimeFromEpochSecond(row.lng("updated"))),
            ApiStatus::new));
    return m;
  }

  /**
   * Async: fetch the request headers the server received for this call, with sensitive values (e.g.
   * {@code Authorization}) redacted server-side. Useful for diagnosing auth issues from a deployed
   * consumer.
   */
  public CompletableFuture<UtilitiesHeadersResponse> headersAsync() {
    RequestSpec spec = RequestSpec.get("headers").unversioned().build();
    return execute(
        spec,
        RequestHeaders.class,
        (d, env, fmt) -> new UtilitiesHeadersResponse(d.headers(), env, fmt));
  }

  /** Sync wrapper for {@link #headersAsync()}; see {@link HttpTransport#joinSync} for semantics. */
  public UtilitiesHeadersResponse headers() {
    return transport.joinSync(headersAsync());
  }

  /**
   * Async: fetch the caller's current quota state and data-tier permissions. Returns a 401 (as
   * {@link com.marketdata.sdk.exception.AuthenticationError}) when no billing plan is associated
   * with the token — the typical use case for {@code validateOnStartup}.
   *
   * <p>Unversioned: the backend mounts the {@code user} router at the API root (no {@code /v1/}
   * prefix), same as {@code /status/} and {@code /headers/}. Hitting {@code /v1/user/} falls
   * through to the global 404 handler.
   */
  public CompletableFuture<UtilitiesUserResponse> userAsync() {
    return execute(
        RequestSpec.get("user").unversioned().build(), User.class, UtilitiesUserResponse::new);
  }

  /** Sync wrapper for {@link #userAsync()}. */
  public UtilitiesUserResponse user() {
    return transport.joinSync(userAsync());
  }

  /**
   * Auth probe used by {@link MarketDataClient}'s startup validation. Hits {@code GET /user/} with
   * a single-attempt policy so the constructor caps at one {@code REQUEST_TIMEOUT} (99 s) instead
   * of burning the default retry budget (~6.75 min worst-case on a down API). A truly unreachable
   * API surfaces within {@code CONNECT_TIMEOUT} (~2 s); a slow-but-TCP-open API can still take up
   * to {@code REQUEST_TIMEOUT} — consumers that need a tighter ceiling should set {@code
   * validateOnStartup = false} and probe themselves with their own deadline. Result is discarded —
   * only the throw shape matters: 401 → {@link com.marketdata.sdk.exception.AuthenticationError},
   * other failures propagate as their typed {@link
   * com.marketdata.sdk.exception.MarketDataException} subtype.
   *
   * <p>Package-private and intent-named: not part of the public API and not an "endpoint" in the
   * §1.2 sense, so ADR-006's sync+async parity does not apply.
   */
  void validateAuth() {
    transport.joinSync(
        execute(
            RequestSpec.get("user").unversioned().build(),
            RetryPolicy.noRetry(),
            User.class,
            UtilitiesUserResponse::new));
  }

  /**
   * Async: fetch the per-service health snapshot of the API. Unversioned ({@code /status/} lives at
   * the API root) and public — works without a token. The server refreshes the snapshot every five
   * minutes; polling more often than that is wasted work.
   */
  public CompletableFuture<UtilitiesStatusResponse> statusAsync() {
    RequestSpec spec = RequestSpec.get("status").unversioned().build();
    return execute(
        spec,
        ApiStatus.class,
        (d, env, fmt) -> new UtilitiesStatusResponse(d.services(), env, fmt));
  }

  /** Sync wrapper for {@link #statusAsync()}. */
  public UtilitiesStatusResponse status() {
    return transport.joinSync(statusAsync());
  }

  // ---------- internal helpers ----------

  private <D, R> CompletableFuture<R> execute(
      RequestSpec spec, Class<D> decodeType, ResponseFactory<D, R> factory) {
    return transport
        .executeAsync(spec)
        .thenApply(env -> factory.create(parser.parse(env, decodeType), env, spec.format()));
  }

  private <D, R> CompletableFuture<R> execute(
      RequestSpec spec, RetryPolicy policy, Class<D> decodeType, ResponseFactory<D, R> factory) {
    return transport
        .executeAsync(spec, policy)
        .thenApply(env -> factory.create(parser.parse(env, decodeType), env, spec.format()));
  }

  @FunctionalInterface
  interface ResponseFactory<D, R> {
    R create(D decoded, HttpResponseEnvelope envelope, Format format);
  }
}
