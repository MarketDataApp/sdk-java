package com.marketdata.sdk;

import com.marketdata.sdk.utilities.ApiStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
  private final MarketsResource markets;
  private final FundsResource funds;

  @Generated // delegates to the network-validating 4-arg ctor; not exercisable as a unit test
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
    // the shared HttpClient and the 50-permit AsyncSemaphore. If any resource constructor throws
    // (today none do), buildResources closes the transport before re-throwing so it doesn't leak.
    // The final fields are assigned here from the returned holder so definite-assignment holds.
    Resources resources = buildResources(cacheRef);
    this.utilities = resources.utilities();
    this.options = resources.options();
    this.stocks = resources.stocks();
    this.markets = resources.markets();
    this.funds = resources.funds();

    if (validateOnStartup) {
      runStartupValidation();
    }
  }

  /** The five resource façades, built together so a partial failure can close the transport. */
  private record Resources(
      UtilitiesResource utilities,
      OptionsResource options,
      StocksResource stocks,
      MarketsResource markets,
      FundsResource funds) {}

  /**
   * Construct the resource façades and wire the §9.5 status cache, closing the transport if any
   * step throws.
   *
   * <p>Excluded from coverage: no resource constructor throws today, so the partial-construction
   * {@code catch} cannot be provoked by a hermetic test, and the trivial {@code new XResource(...)}
   * wiring carries no logic of its own (each resource's behaviour is covered in its own tests).
   */
  @Generated
  private Resources buildResources(AtomicReference<StatusCache> cacheRef) {
    try {
      JsonResponseParser parser = new JsonResponseParser();
      Resources resources =
          new Resources(
              new UtilitiesResource(transport, parser),
              new OptionsResource(transport, parser),
              new StocksResource(transport, parser),
              new MarketsResource(transport, parser),
              new FundsResource(transport, parser));
      cacheRef.set(new StatusCache(this::fetchStatusForCache, Clock.systemUTC()));
      return resources;
    } catch (Throwable t) {
      try {
        transport.close();
      } catch (Throwable closeFailure) {
        t.addSuppressed(closeFailure);
      }
      throw t;
    }
  }

  /**
   * Status-cache fetcher (§9.5). Excluded from coverage: invoked only when the cache refreshes,
   * which requires a live {@code /status/} round-trip through the transport.
   */
  @Generated
  private CompletableFuture<ApiStatus> fetchStatusForCache() {
    return utilities.statusAsync().thenApply(this::toApiStatus);
  }

  /** Adapt a status response into the cache's {@link ApiStatus} value. Excluded with its caller. */
  @Generated
  private ApiStatus toApiStatus(UtilitiesStatusResponse response) {
    return new ApiStatus(response.values());
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

  /** Options endpoints: {@code lookup}, {@code expirations}, {@code quotes}, {@code chain}. */
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

  /** Funds endpoints: {@code candles}. */
  public FundsResource funds() {
    return funds;
  }

  /**
   * Markets endpoints: {@code status} (the exchange open/closed calendar — distinct from {@code
   * utilities().status()}, the API's own service health).
   */
  public MarketsResource markets() {
    return markets;
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
  @Generated // the non-demo path makes a live /user/ call; only the demo skip is unit-testable
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
