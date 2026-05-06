package com.marketdata.sdk.utilities;

import com.marketdata.sdk.internal.http.HttpTransport;
import com.marketdata.sdk.internal.http.RequestSpec;
import java.util.concurrent.CompletableFuture;

/**
 * Façade for the {@code utilities} resource group covering all three methods named in SDK
 * requirements §2.2: {@link #status}, {@link #headers}, {@link #user}.
 *
 * <p>Note that {@code /status/} and {@code /headers/} live at the API root (no {@code /v1/}
 * prefix), unlike most endpoints — that's why their {@link RequestSpec} use {@link
 * RequestSpec#getAtRoot} instead of the regular versioned {@link RequestSpec#get}.
 *
 * <p>Per ADR-006 every endpoint exposes a sync and an {@code …Async} variant.
 */
public final class UtilitiesResource {

  private static final String STATUS_PATH = "status";
  private static final String HEADERS_PATH = "headers";
  private static final String USER_PATH = "user";

  private final HttpTransport transport;

  /**
   * Constructed by {@code MarketDataClient}; consumers reach a {@code UtilitiesResource} via {@code
   * client.utilities()}.
   */
  public UtilitiesResource(HttpTransport transport) {
    this.transport = transport;
  }

  // ---------- /status/ — service health ----------

  /**
   * API service health snapshot. Equivalent to {@code GET /status/} (root-level, no {@code /v1/}
   * prefix). Does not require authentication.
   */
  public ServiceStatus status() {
    return transport.executeSync(RequestSpec.getAtRoot(STATUS_PATH).build(), ServiceStatus.class);
  }

  /** Async variant of {@link #status()}. */
  public CompletableFuture<ServiceStatus> statusAsync() {
    return transport.executeAsync(RequestSpec.getAtRoot(STATUS_PATH).build(), ServiceStatus.class);
  }

  // ---------- /headers/ — debug echo ----------

  /**
   * Returns the HTTP headers the API saw on this request. Equivalent to {@code GET /headers/}
   * (root-level). Useful for debugging proxies, auth, or User-Agent issues.
   */
  public RequestHeaders headers() {
    return transport.executeSync(RequestSpec.getAtRoot(HEADERS_PATH).build(), RequestHeaders.class);
  }

  /** Async variant of {@link #headers()}. */
  public CompletableFuture<RequestHeaders> headersAsync() {
    return transport.executeAsync(
        RequestSpec.getAtRoot(HEADERS_PATH).build(), RequestHeaders.class);
  }

  // ---------- /user/ — account info (root path, no /v1/ prefix) ----------

  /**
   * Account-level info for the token in use. Equivalent to {@code GET /user/} — root-level, like
   * {@link #status()} and {@link #headers()}. Despite serving user-scoped data, this endpoint lives
   * outside the {@code /v1/} surface; the user viewset on the API is mounted under the admin router
   * which binds to the root.
   *
   * <p>Used internally by {@code MarketDataClient} during construction when {@code
   * validateOnStartup} is enabled (SDK requirements §5) — failure to reach this endpoint with a
   * valid token produces an {@code AuthenticationException} from the constructor itself.
   */
  public UserInfo user() {
    return transport.executeSync(RequestSpec.getAtRoot(USER_PATH).build(), UserInfo.class);
  }

  /** Async variant of {@link #user()}. */
  public CompletableFuture<UserInfo> userAsync() {
    return transport.executeAsync(RequestSpec.getAtRoot(USER_PATH).build(), UserInfo.class);
  }
}
