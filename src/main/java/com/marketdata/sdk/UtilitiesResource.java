package com.marketdata.sdk;

import com.marketdata.sdk.exception.MarketDataException;
import com.marketdata.sdk.utilities.RequestHeaders;
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
}
