package com.marketdata.sdk;

import com.marketdata.sdk.utilities.ApiStatus;
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
  private final OptionsResource options;
  private final StocksResource stocks;

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
    try {
      this.config =
          Configuration.resolve(apiKey, baseUrl, apiVersion, env, dotEnvPath, pendingWarnings::add);
    } catch (RuntimeException e) {
      // Issue #25: if resolve fails (typically IAE — invalid baseUrl/apiVersion/apiKey from the
      // cascade), the consumer would otherwise lose any .env warnings collected so far. That
      // hides the real story: e.g. "your .env was unreadable, so the missing baseUrl fell
      // through to a default that conflicts with your explicit apiVersion". Attach each warning
      // as a suppressed exception so the diagnostic trail surfaces in the same stack trace.
      attachWarningsAsSuppressed(e, pendingWarnings);
      throw e;
    }
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
    // Partial-construction guard: from here on the transport is a live AutoCloseable that holds
    // the shared HttpClient and the 50-permit AsyncSemaphore. If any subsequent constructor
    // throws (today none do, but a future change in UtilitiesResource / StatusCache could),
    // the caller never receives a reference, their try-with-resources never fires, and the
    // transport leaks until GC. Close it explicitly and surface the close failure (if any) as
    // a suppressed exception on the primary cause — same pattern runStartupValidation already
    // uses for the validation path.
    try {
      JsonResponseParser parser = new JsonResponseParser();
      this.utilities = new UtilitiesResource(transport, parser);
      this.options = new OptionsResource(transport, parser);
      this.stocks = new StocksResource(transport, parser);
      cacheRef.set(
          new StatusCache(
              () -> utilities.statusAsync().thenApply(r -> new ApiStatus(r.values())),
              Clock.systemUTC()));
    } catch (Throwable t) {
      try {
        transport.close();
      } catch (Throwable closeFailure) {
        t.addSuppressed(closeFailure);
      }
      throw t;
    }

    if (validateOnStartup) {
      runStartupValidation();
    }
  }

  /**
   * Attach each pending {@code .env} warning to {@code primary} as a suppressed exception so the
   * diagnostic trail survives a configuration-resolve failure. {@link Throwable#getCause()} would
   * conflict with the actual cause of the IAE; suppressed is the right surface for "additional
   * context the consumer should see alongside the primary failure".
   */
  private static void attachWarningsAsSuppressed(
      RuntimeException primary, List<DotEnvLoader.Warning> warnings) {
    for (DotEnvLoader.Warning w : warnings) {
      Throwable wrapper =
          new RuntimeException("[.env " + w.level() + "] " + w.message(), w.cause());
      primary.addSuppressed(wrapper);
    }
  }

  /** System endpoints documented at the API root: {@code /headers/} (and more to come). */
  public UtilitiesResource utilities() {
    return utilities;
  }

  /**
   * Options endpoints: {@code lookup}, {@code expirations}, {@code strikes}, {@code quotes}, {@code
   * chain}.
   */
  public OptionsResource options() {
    return options;
  }

  /**
   * Stocks endpoints: {@code candles}, {@code quote}, {@code quotes}, {@code prices}, {@code news},
   * {@code earnings}.
   */
  public StocksResource stocks() {
    return stocks;
  }

  /**
   * Fire a single call to {@code GET /user/} to confirm the token is accepted and a billing plan is
   * attached (SDK requirements §5). A 401 surfaces as {@link
   * com.marketdata.sdk.exception.AuthenticationError} directly via the sync wrapper. On any failure
   * we close the transport before re-throwing so a partially-constructed client doesn't leak its
   * HttpClient — the caller's try-with-resources is never triggered if the constructor itself
   * fails.
   *
   * <p>Skipped in demo mode: there is no token to validate, and {@code /user/} would
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
