package com.marketdata.sdk;

import com.marketdata.sdk.internal.Configuration;
import com.marketdata.sdk.internal.EnvVars;
import com.marketdata.sdk.internal.Tokens;
import com.marketdata.sdk.internal.Version;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Entry point to the Market Data Java SDK.
 *
 * <p>One {@code MarketDataClient} per application. Holds a single shared {@link HttpClient}
 * (HTTP/2, 2 s connect timeout) for connection pooling (ADR-004) and a 50-permit semaphore that
 * gates the global concurrency pool required by SDK requirements §12.
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
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(99);

  /** SDK requirements §10: fixed 2-second connect timeout. */
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** SDK requirements §12: maximum concurrent in-flight requests per client. */
  public static final int CONCURRENCY_LIMIT = 50;

  private static final Logger LOG = Logger.getLogger(MarketDataClient.class.getName());

  private final HttpClient httpClient;
  private final Semaphore concurrencyPermits;
  private final AtomicReference<@Nullable RateLimits> latestRateLimits = new AtomicReference<>();

  private final @Nullable String token;
  private final String baseUrl;
  private final String apiVersion;
  private final String userAgent;
  private final boolean demoMode;
  private final boolean validateOnStartup;

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

    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.concurrencyPermits = new Semaphore(CONCURRENCY_LIMIT);

    LOG.log(
        Level.INFO,
        "Initialized Market Data SDK {0} (baseUrl={1}, apiVersion={2}, demoMode={3})",
        new Object[] {Version.current(), this.baseUrl, this.apiVersion, this.demoMode});
    if (this.demoMode) {
      LOG.warning(
          "No API token provided — running in demo mode. Authenticated endpoints will"
              + " fail; rate-limit initialization is skipped.");
    } else if (LOG.isLoggable(Level.FINE)) {
      LOG.log(Level.FINE, "Token: {0}", Tokens.redact(this.token));
    }

    // SDK requirements §5: validate on startup by default. The actual
    // /user/ call lands with the request layer; this flag is the seam.
  }

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
    return latestRateLimits.get();
  }

  @Override
  public void close() {
    // java.net.http.HttpClient gained explicit close() in JDK 21.
    // While the minimum target is JDK 17 (ADR-002), this method is a
    // no-op: the JVM releases the executor and connection pool on
    // process exit. Revisit if/when the minimum bumps to 21+.
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
