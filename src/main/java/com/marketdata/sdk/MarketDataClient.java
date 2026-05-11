package com.marketdata.sdk;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Entry point to the Market Data Java SDK.
 *
 * <p>One {@code MarketDataClient} per application. Resource façades (e.g. {@link #markets()}) are
 * accessed through the client; all HTTP-shaped concerns (connection pooling, HTTP/2, the global
 * concurrency semaphore, rate-limit header parsing) live in the internal {@link HttpTransport} the
 * client owns.
 *
 * <p>Two constructors:
 *
 * <ul>
 *   <li>{@link #MarketDataClient()} — production path. Resolves everything from the cascade in §4
 *       ({@code MARKETDATA_*} environment variable → value in a {@code .env} file → built-in
 *       default). With no token in the cascade, enters <em>demo mode</em> — authenticated endpoints
 *       will fail and the {@code Authorization} header is omitted.
 *   <li>{@link #MarketDataClient(String, String, String, boolean)} — explicit-control path for
 *       tests and short-lived runtimes. Each parameter may still be {@code null} to defer to the
 *       cascade for that single value.
 * </ul>
 *
 * <p>Instances are immutable: every field is {@code final} and assigned in the constructor.
 */
public final class MarketDataClient implements AutoCloseable {

  /** SDK requirements §10: fixed 99-second per-request timeout. */
  public static final Duration REQUEST_TIMEOUT = HttpTransport.REQUEST_TIMEOUT;

  /** SDK requirements §10: fixed 2-second connect timeout. */
  public static final Duration CONNECT_TIMEOUT = HttpTransport.CONNECT_TIMEOUT;

  /** SDK requirements §12: maximum concurrent in-flight requests per client. */
  public static final int CONCURRENCY_LIMIT = HttpTransport.CONCURRENCY_LIMIT;

  private static final Logger LOG = Logger.getLogger(MarketDataClient.class.getName());

  private final HttpTransport transport;

  private final @Nullable String token;
  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final boolean demoMode;
  private final boolean validateOnStartup;

  // Resources — eagerly constructed; one record-shaped object per resource group.
  private final MarketsResource markets;

  /**
   * Production constructor. Resolves all settings from the configuration cascade in SDK
   * requirements §4 (env var → {@code .env} → built-in default) and enables startup validation.
   *
   * <p>Equivalent to {@link #MarketDataClient(String, String, String, boolean) new
   * MarketDataClient(null, null, null, true)}.
   */
  public MarketDataClient() {
    this(null, null, null, true);
  }

  /**
   * Explicit-control constructor for tests and short-lived runtimes. Each of {@code apiKey}, {@code
   * baseUrl}, and {@code apiVersion} may be {@code null} to defer to the cascade in §4 for that
   * single value.
   *
   * @param apiKey explicit API token, or {@code null} to resolve from {@code MARKETDATA_TOKEN} →
   *     {@code .env} → demo mode
   * @param baseUrl override the API base URL, or {@code null} to resolve to {@link
   *     Configuration#DEFAULT_BASE_URL}
   * @param apiVersion override the API version segment, or {@code null} to resolve to {@link
   *     Configuration#DEFAULT_API_VERSION}
   * @param validateOnStartup whether to validate the token on construction by calling {@code
   *     /user/} (SDK requirements §5). Pass {@code false} for short-lived runtimes where the
   *     startup hit is undesirable.
   */
  public MarketDataClient(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String apiVersion,
      boolean validateOnStartup) {
    Configuration config = Configuration.loadFromProcess();
    this.token = config.resolve(apiKey, EnvVars.TOKEN);
    this.baseUrl =
        trimTrailingSlash(
            config.resolveOrDefault(baseUrl, EnvVars.BASE_URL, Configuration.DEFAULT_BASE_URL));
    this.apiVersion =
        config.resolveOrDefault(apiVersion, EnvVars.API_VERSION, Configuration.DEFAULT_API_VERSION);
    this.demoMode = this.token == null;
    this.validateOnStartup = validateOnStartup;
    this.userAgent = "marketdata-sdk-java/" + Version.current();

    this.transport = new HttpTransport(this.baseUrl, this.apiVersion, this.userAgent, this.token);
    this.markets = new MarketsResource(this.transport);

    LOG.log(
        Level.INFO,
        "Initialized Market Data SDK {0} (baseUrl={1}, apiVersion={2}, demoMode={3})",
        new Object[] {Version.current(), this.baseUrl, this.apiVersion, this.demoMode});
    if (this.demoMode) {
      LOG.warning(
          "No API token provided — running in demo mode. Authenticated endpoints will fail with"
              + " AuthenticationError on first call.");
    } else if (LOG.isLoggable(Level.FINE)) {
      LOG.log(Level.FINE, "Token: {0}", Tokens.redact(this.token));
    }

    // SDK requirements §5: validate on startup by default. The actual
    // /user/ call lands with the user resource; this flag is the seam.
  }

  // ---------------------------------------------------------------------
  // Resource accessors
  // ---------------------------------------------------------------------

  /** Façade for the {@code /v1/markets/*} endpoint group. */
  public MarketsResource markets() {
    return markets;
  }

  // ---------------------------------------------------------------------
  // Configuration accessors
  // ---------------------------------------------------------------------

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public boolean isDemoMode() {
    return demoMode;
  }

  public boolean isValidateOnStartup() {
    return validateOnStartup;
  }

  /**
   * Latest client-level rate-limit snapshot, or {@code null} if no rate-limit-bearing response has
   * been received yet. Once populated, the snapshot persists across subsequent calls — a successful
   * response that arrives without {@code x-api-ratelimit-*} headers (e.g. during a server-side
   * middleware outage) does not clear it.
   */
  public @Nullable RateLimits getRateLimits() {
    return transport.getLatestRateLimits();
  }

  @Override
  public void close() {
    transport.close();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
