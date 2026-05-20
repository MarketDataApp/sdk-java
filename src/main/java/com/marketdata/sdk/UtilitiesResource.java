package com.marketdata.sdk;

import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.User;
import java.util.concurrent.CompletableFuture;

/**
 * System endpoints documented at {@code https://api.marketdata.app/docs/api/utilities/}. None of
 * them are versioned ({@code /v1/}); they live at the API root.
 *
 * <p>Constructed once per {@link MarketDataClient}; the consumer reaches it through {@code
 * client.utilities()}. Constructor is package-private (ADR-007) — consumers cannot instantiate.
 *
 * <p>Every endpoint returns a {@link Response} carrying both the typed model and the raw body so
 * consumers can access §13.5 response features ({@code isCsv()}, {@code saveToFile()}, …) without
 * the resource caring about format choice.
 */
public final class UtilitiesResource {

  private final HttpTransport transport;
  private final JsonResponseParser parser;

  UtilitiesResource(HttpTransport transport, JsonResponseParser parser) {
    this.transport = transport;
    this.parser = parser;
  }

  /**
   * Async: fetch the request headers the server received for this call, with sensitive values (e.g.
   * {@code Authorization}) redacted server-side. Useful for diagnosing auth issues from a deployed
   * consumer.
   */
  public CompletableFuture<Response<RequestHeaders>> headersAsync() {
    RequestSpec spec = RequestSpec.get("headers").unversioned().build();
    return executeAndWrap(spec, RequestHeaders.class);
  }

  /** Sync wrapper for {@link #headersAsync()}; see {@link HttpTransport#joinSync} for semantics. */
  public Response<RequestHeaders> headers() {
    return transport.joinSync(headersAsync());
  }

  /**
   * Async: fetch the caller's current quota state and data-tier permissions. Returns a 401 (as
   * {@link com.marketdata.sdk.exception.AuthenticationError}) when no billing plan is associated
   * with the token — the typical use case for {@code validateOnStartup}.
   */
  public CompletableFuture<Response<User>> userAsync() {
    return executeAndWrap(RequestSpec.get("user").build(), User.class);
  }

  /** Sync wrapper for {@link #userAsync()}. */
  public Response<User> user() {
    return transport.joinSync(userAsync());
  }

  /**
   * Auth probe used by {@link MarketDataClient}'s startup validation. Hits {@code GET /v1/user/}
   * with a single-attempt policy so the constructor caps at one {@code REQUEST_TIMEOUT} (99 s)
   * instead of burning the default retry budget (~6.75 min worst-case on a down API). A truly
   * unreachable API surfaces within {@code CONNECT_TIMEOUT} (~2 s); a slow-but-TCP-open API can
   * still take up to {@code REQUEST_TIMEOUT} — consumers that need a tighter ceiling should set
   * {@code validateOnStartup = false} and probe themselves with their own deadline. Result is
   * discarded — only the throw shape matters: 401 → {@link
   * com.marketdata.sdk.exception.AuthenticationError}, other failures propagate as their typed
   * {@link com.marketdata.sdk.exception.MarketDataException} subtype.
   *
   * <p>Package-private and intent-named: not part of the public API and not an "endpoint" in the
   * §1.2 sense, so ADR-006's sync+async parity does not apply.
   */
  void validateAuth() {
    transport.joinSync(
        executeAndWrap(RequestSpec.get("user").build(), RetryPolicy.noRetry(), User.class));
  }

  /**
   * Async: fetch the per-service health snapshot of the API. Unversioned ({@code /status/} lives at
   * the API root) and public — works without a token. The server refreshes the snapshot every five
   * minutes; polling more often than that is wasted work.
   */
  public CompletableFuture<Response<ApiStatus>> statusAsync() {
    RequestSpec spec = RequestSpec.get("status").unversioned().build();
    return executeAndWrap(spec, ApiStatus.class);
  }

  /** Sync wrapper for {@link #statusAsync()}. */
  public Response<ApiStatus> status() {
    return transport.joinSync(statusAsync());
  }

  // ---------- internal helpers ----------

  private <T> CompletableFuture<Response<T>> executeAndWrap(RequestSpec spec, Class<T> type) {
    return transport
        .executeAsync(spec)
        .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
  }

  private <T> CompletableFuture<Response<T>> executeAndWrap(
      RequestSpec spec, RetryPolicy policy, Class<T> type) {
    return transport
        .executeAsync(spec, policy)
        .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
  }
}
