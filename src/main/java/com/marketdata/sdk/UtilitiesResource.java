package com.marketdata.sdk;

import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.User;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * System endpoints documented at {@code https://api.marketdata.app/docs/api/utilities/}. None of
 * them are versioned ({@code /v1/}); they live at the API root.
 *
 * <p>Constructed once per {@link MarketDataClient}; the consumer reaches it through {@code
 * client.utilities()}. Constructor is package-private (ADR-007) — consumers cannot instantiate.
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
  public CompletableFuture<RequestHeaders> headersAsync() {
    RequestSpec spec = RequestSpec.get("headers").unversioned().build();
    return transport.executeAsync(spec).thenApply(env -> parser.parse(env, RequestHeaders.class));
  }

  /**
   * Sync wrapper for {@link #headersAsync()}. Per ADR-006, calls {@code .join()} and unwraps {@link
   * CompletionException} so the caller sees the underlying {@link MarketDataException} directly.
   */
  public RequestHeaders headers() {
    try {
      return headersAsync().join();
    } catch (CompletionException e) {
      throw HttpTransport.asRuntime(e.getCause());
    } catch (CancellationException e) {
      throw HttpTransport.asRuntime(e);
    }
  }

  /**
   * Async: fetch the caller's current quota state and data-tier permissions. Returns a 401 (as
   * {@link com.marketdata.sdk.exception.AuthenticationError}) when no billing plan is associated
   * with the token — the typical use case for {@code validateOnStartup}.
   */
  public CompletableFuture<User> userAsync() {
    RequestSpec spec = RequestSpec.get("user").build();
    return transport.executeAsync(spec).thenApply(env -> parser.parse(env, User.class));
  }

  /**
   * Sync wrapper for {@link #userAsync()}; same {@link CompletionException}-unwrapping semantics as
   * {@link #headers()}.
   */
  public User user() {
    try {
      return userAsync().join();
    } catch (CompletionException e) {
      throw HttpTransport.asRuntime(e.getCause());
    } catch (CancellationException e) {
      throw HttpTransport.asRuntime(e);
    }
  }

  /**
   * Async: fetch the per-service health snapshot of the API. Unversioned ({@code /status/} lives at
   * the API root) and public — works without a token. The server refreshes the snapshot every five
   * minutes; polling more often than that is wasted work.
   */
  public CompletableFuture<ApiStatus> statusAsync() {
    RequestSpec spec = RequestSpec.get("status").unversioned().build();
    return transport.executeAsync(spec).thenApply(env -> parser.parse(env, ApiStatus.class));
  }

  /**
   * Sync wrapper for {@link #statusAsync()}; same {@link CompletionException}-unwrapping semantics
   * as {@link #headers()} and {@link #user()}.
   */
  public ApiStatus status() {
    try {
      return statusAsync().join();
    } catch (CompletionException e) {
      throw HttpTransport.asRuntime(e.getCause());
    } catch (CancellationException e) {
      throw HttpTransport.asRuntime(e);
    }
  }
}
