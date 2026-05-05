package com.marketdata.sdk;

import com.marketdata.sdk.internal.Configuration;
import com.marketdata.sdk.internal.EnvVars;
import com.marketdata.sdk.internal.Tokens;
import com.marketdata.sdk.internal.Version;
import com.marketdata.sdk.internal.http.HttpTransport;
import com.marketdata.sdk.markets.MarketsResource;
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
 * <p>Construction follows the configuration cascade in SDK requirements §4: explicit builder values
 * → {@code MARKETDATA_*} environment variables → values in a {@code .env} file in the working
 * directory → built-in defaults. Pass no token to enter <em>demo mode</em> (authenticated endpoints
 * will fail; the {@code Authorization} header is omitted).
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

  // Resources — lazily? eagerly? eager keeps the API simple, the cost is
  // one record-shaped object per resource group. Worth revisiting if the
  // resource count grows large.
  private final MarketsResource markets;

  private MarketDataClient(Builder builder) {
    Configuration config = Configuration.loadFromProcess();
    this.token = config.resolve(builder.apiKey, EnvVars.TOKEN);
    this.baseUrl =
        trimTrailingSlash(
            config.resolveOrDefault(
                builder.baseUrl, EnvVars.BASE_URL, Configuration.DEFAULT_BASE_URL));
    this.apiVersion =
        config.resolveOrDefault(
            builder.apiVersion, EnvVars.API_VERSION, Configuration.DEFAULT_API_VERSION);
    this.demoMode = this.token == null;
    this.validateOnStartup = builder.validateOnStartup;
    this.userAgent = "marketdata-sdk-java/" + Version.current();

    this.transport = new HttpTransport(this.baseUrl, this.apiVersion, this.userAgent, this.token);
    this.markets = new MarketsResource(this.transport);

    LOG.log(
        Level.INFO,
        "Initialized Market Data SDK {0} (baseUrl={1}, apiVersion={2}, demoMode={3})",
        new Object[] {Version.current(), baseUrl, apiVersion, demoMode});
    if (demoMode) {
      LOG.warning(
          "No API token provided — running in demo mode. Authenticated endpoints will"
              + " fail; rate-limit initialization is skipped.");
    } else if (LOG.isLoggable(Level.FINE)) {
      LOG.log(Level.FINE, "Token: {0}", Tokens.redact(token));
    }

    // SDK requirements §5: validate on startup by default. The actual
    // /user/ call lands with the user resource; this flag is the seam.
  }

  public static Builder builder() {
    return new Builder();
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

  /** Latest client-level rate-limit snapshot, or {@code null} if none has been received yet. */
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

  public static final class Builder {
    private @Nullable String apiKey;
    private @Nullable String baseUrl;
    private @Nullable String apiVersion;
    private boolean validateOnStartup = true;

    private Builder() {}

    /** Override the API token; otherwise resolved from {@code MARKETDATA_TOKEN} or {@code .env}. */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Override the base URL (default {@value Configuration#DEFAULT_BASE_URL}). */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** Override the API version (default {@value Configuration#DEFAULT_API_VERSION}). */
    public Builder apiVersion(String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    /**
     * Whether to validate the token at construction by calling {@code /user/} (SDK requirements
     * §5). Defaults to {@code true}. Disable for short-lived runtimes where the startup hit is
     * undesirable.
     */
    public Builder validateOnStartup(boolean validateOnStartup) {
      this.validateOnStartup = validateOnStartup;
      return this;
    }

    public MarketDataClient build() {
      return new MarketDataClient(this);
    }
  }
}
