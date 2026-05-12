package com.marketdata.sdk;

import java.time.Duration;
import java.util.Locale;
import java.util.logging.Handler;
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
   * MarketDataClient(null, null, null, true)} — see that constructor's side-effects paragraph for
   * the network IO performed when a token is present.
   */
  public MarketDataClient() {
    this(null, null, null, true);
  }

  /**
   * Explicit-control constructor for tests and short-lived runtimes. Each of {@code apiKey}, {@code
   * baseUrl}, and {@code apiVersion} may be {@code null} to defer to the cascade in §4 for that
   * single value.
   *
   * <p><strong>Side effects when {@code validateOnStartup=true}:</strong> the constructor performs
   * a synchronous {@code GET /user/} HTTP request against {@code baseUrl} to verify the token. That
   * means construction:
   *
   * <ul>
   *   <li>blocks the calling thread for up to the per-request timeout (99 s) on a slow or
   *       unreachable server,
   *   <li>can throw {@link com.marketdata.sdk.exception.AuthenticationError} when the server
   *       rejects the token with 401, and
   *   <li>can throw {@link com.marketdata.sdk.exception.NetworkError} on connection failure.
   * </ul>
   *
   * Pass {@code false} for tests, latency-sensitive cold paths (e.g. serverless), or any context
   * where a "pure" constructor without network IO is required. Demo mode (no token) also skips the
   * call regardless of the flag.
   *
   * @param apiKey explicit API token, or {@code null} to resolve from {@code MARKETDATA_TOKEN} →
   *     {@code .env} → demo mode
   * @param baseUrl override the API base URL, or {@code null} to resolve to {@link
   *     Configuration#DEFAULT_BASE_URL}
   * @param apiVersion override the API version segment, or {@code null} to resolve to {@link
   *     Configuration#DEFAULT_API_VERSION}
   * @param validateOnStartup whether to validate the token on construction by calling {@code
   *     /user/} (SDK requirements §5). See the side-effects paragraph above.
   */
  public MarketDataClient(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String apiVersion,
      boolean validateOnStartup) {
    Configuration config = Configuration.loadFromProcess();
    // SDK requirements §7: apply MARKETDATA_LOGGING_LEVEL (if set) before any LOG.log call
    // below, otherwise the INFO line for client initialization would be filtered out when the
    // user expected FINE.
    configureLogging(config);
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

    // SDK requirements §5: validate the token at startup by hitting /user/. Skipped in demo
    // mode (no token to validate) and when the caller opted out via the 4-arg constructor.
    // Failure surfaces immediately as AuthenticationError (401) or NetworkError (unreachable
    // server), so the caller never gets a half-constructed client.
    if (this.validateOnStartup && !this.demoMode) {
      this.transport.validateToken();
      LOG.log(Level.FINE, "Token validated against /user/.");
    }
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

  // ---------------------------------------------------------------------
  // SDK requirements §7 — logging level + formatter wiring
  // ---------------------------------------------------------------------

  /**
   * Name of the package-level logger that controls every {@code com.marketdata.sdk.*} logger
   * (children inherit unless they install their own handler).
   */
  static final String SDK_LOGGER_NAME = "com.marketdata.sdk";

  /**
   * Applies {@code MARKETDATA_LOGGING_LEVEL} (resolved through the config cascade) to the SDK's
   * logger and installs a spec-shaped {@link MarketDataLogFormatter} on a dedicated handler if one
   * isn't already installed. Idempotent — calling repeatedly only refreshes the level.
   *
   * <p>Package-private + static so the constructor wires it and tests can drive it directly with a
   * synthetic {@link Configuration} without depending on real process env vars.
   *
   * <p>When the env var is unset (or blank, or malformed), the method is a no-op — the SDK inherits
   * whatever {@code java.util.logging} configuration the host JVM has, which is what library code
   * is expected to do.
   */
  static void configureLogging(Configuration config) {
    String requestedLevel = config.resolve(null, EnvVars.LOGGING_LEVEL);
    if (requestedLevel == null || requestedLevel.isBlank()) {
      return;
    }
    Level parsed;
    try {
      parsed = Level.parse(requestedLevel.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      LOG.warning("Ignoring invalid MARKETDATA_LOGGING_LEVEL=\"" + requestedLevel + "\"");
      return;
    }
    Logger sdkLogger = Logger.getLogger(SDK_LOGGER_NAME);
    sdkLogger.setLevel(parsed);
    if (!hasSdkHandler(sdkLogger)) {
      Handler handler = new java.util.logging.ConsoleHandler();
      handler.setFormatter(new MarketDataLogFormatter());
      handler.setLevel(parsed);
      sdkLogger.addHandler(handler);
      // Bypass the root logger's default handlers; they would otherwise re-emit each record
      // with the JVM default formatter and produce duplicate, badly-shaped lines.
      sdkLogger.setUseParentHandlers(false);
    } else {
      for (Handler h : sdkLogger.getHandlers()) {
        if (isSdkHandler(h)) {
          h.setLevel(parsed);
        }
      }
    }
  }

  private static boolean hasSdkHandler(Logger logger) {
    for (Handler h : logger.getHandlers()) {
      if (isSdkHandler(h)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSdkHandler(Handler h) {
    return h.getFormatter() instanceof MarketDataLogFormatter;
  }
}
