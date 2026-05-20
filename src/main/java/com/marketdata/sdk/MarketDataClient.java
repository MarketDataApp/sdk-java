package com.marketdata.sdk;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

public final class MarketDataClient implements AutoCloseable {

  // §7: one logger for the whole SDK (com.marketdata.sdk). Consumers configure or attach handlers
  // to that single name; consolidating here keeps MarketDataLogging's consumer-pre-config
  // detection and useParentHandlers=false guard aware of every emission path. Parity with the
  // Python SDK (single marketdata.logger).
  private static final Logger LOGGER = Logger.getLogger(MarketDataLogging.SDK_LOGGER_NAME);

  private final Configuration config;
  private final HttpTransport transport;
  private final UtilitiesResource utilities;

  public MarketDataClient() {
    this(null, null, null, true);
  }

  public MarketDataClient(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String apiVersion,
      boolean validateOnStartup) {
    this(
        apiKey,
        baseUrl,
        apiVersion,
        validateOnStartup,
        EnvVars.systemLookup(),
        Configuration.DEFAULT_DOTENV_PATH);
  }

  /**
   * Package-private ctor with the env-lookup and dotEnv-path seams exposed so tests can drive the
   * configuration cascade hermetically. The public 4-arg ctor delegates here with {@link
   * EnvVars#systemLookup()} and {@link Configuration#DEFAULT_DOTENV_PATH}.
   */
  MarketDataClient(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String apiVersion,
      boolean validateOnStartup,
      Function<String, @Nullable String> env,
      Path dotEnvPath) {
    // Collect warnings from the configuration cascade (e.g. an unreadable .env) instead of
    // letting DotEnvLoader log them directly. The loader runs BEFORE MarketDataLogging.configure
    // — emitting WARNINGs there would land on an unconfigured JUL logger (wrong format,
    // possibly invisible), undermining the breadcrumb the WARNING exists to provide.
    List<DotEnvLoader.Warning> pendingWarnings = new ArrayList<>();
    this.config =
        Configuration.resolve(apiKey, baseUrl, apiVersion, env, dotEnvPath, pendingWarnings::add);
    MarketDataLogging.configure(config.loggingLevel());
    for (DotEnvLoader.Warning w : pendingWarnings) {
      LOGGER.log(w.level(), w.message(), w.cause());
    }
    LOGGER.info(
        () ->
            "MarketDataClient initialized: baseUrl="
                + config.baseUrl()
                + ", apiVersion="
                + config.apiVersion()
                + ", token="
                + Tokens.redact(config.apiKey())
                + ", demoMode="
                + DemoMode.isDemo(config));

    // §9.5: the status cache pre-checks /status/ before retrying 5xx. The cache's fetcher uses
    // `utilities.statusAsync()`, which goes through this transport — a chicken-and-egg. We
    // resolve it with a deferred reference: the transport reads the cache through a supplier,
    // which returns null until the cache is constructed (just below this transport instance).
    AtomicReference<StatusCache> cacheRef = new AtomicReference<>();
    this.transport =
        HttpTransport.withDefaults(
            config.baseUrl(),
            config.apiVersion(),
            "marketdata-sdk-java/" + Version.sdkVersion(),
            config.apiKey(),
            cacheRef::get);
    JsonResponseParser parser = new JsonResponseParser();
    this.utilities = new UtilitiesResource(transport, parser);
    cacheRef.set(
        new StatusCache(
            () -> utilities.statusAsync().thenApply(Response::data), Clock.systemUTC()));

    if (validateOnStartup) {
      runStartupValidation();
    }
  }

  /** System endpoints documented at the API root: {@code /headers/} (and more to come). */
  public UtilitiesResource utilities() {
    return utilities;
  }

  /**
   * Fire a single call to {@code GET /v1/user/} to confirm the token is accepted and a billing plan
   * is attached (SDK requirements §5). A 401 surfaces as {@link
   * com.marketdata.sdk.exception.AuthenticationError} directly via the sync wrapper. On any failure
   * we close the transport before re-throwing so a partially-constructed client doesn't leak its
   * HttpClient — the caller's try-with-resources is never triggered if the constructor itself
   * fails.
   *
   * <p>Skipped in demo mode: there is no token to validate, and {@code /v1/user/} would
   * deterministically return 401, breaking construction for any consumer who instantiates the SDK
   * without a token configured (the "I want to kick the tires" path).
   *
   * <p>Package-private so the demo-mode skip can be tested hermetically (i.e. without depending on
   * whether {@code MARKETDATA_TOKEN} is set in the runner's environment).
   */
  void runStartupValidation() {
    if (DemoMode.isDemo(config)) {
      LOGGER.info(() -> "validateOnStartup skipped: demo mode is active (no token configured).");
      return;
    }
    // Intent-named auth probe in UtilitiesResource — single-attempt so a slow/down API surfaces
    // here within seconds instead of burning the default retry budget (~6.75 min).
    try {
      utilities.validateAuth();
    } catch (Throwable t) {
      try {
        close();
      } catch (Throwable closeFailure) {
        t.addSuppressed(closeFailure);
      }
      throw t;
    }
  }

  /**
   * Latest rate-limit snapshot recorded from any successful response. Returns {@code null} until
   * the first rate-limit-bearing response has arrived — a real {@code remaining=0} reported by the
   * server stays observable as {@code snapshot.remaining() == 0}, distinct from "no snapshot yet".
   */
  public @Nullable RateLimitSnapshot getRateLimits() {
    return transport.getLatestRateLimits();
  }

  @Override
  public void close() {
    transport.close();
  }

  @Override
  public String toString() {
    return "MarketDataClient[baseUrl="
        + config.baseUrl()
        + ", apiVersion="
        + config.apiVersion()
        + ", apiKey="
        + Tokens.redact(config.apiKey())
        + ", demoMode="
        + DemoMode.isDemo(config)
        + "]";
  }
}
