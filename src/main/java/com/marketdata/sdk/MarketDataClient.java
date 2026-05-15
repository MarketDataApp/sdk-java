package com.marketdata.sdk;

import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class MarketDataClient implements AutoCloseable {

  private final Configuration config;
  private volatile RateLimitSnapshot rateLimits;

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
        Configuration.DEFAULT_DOTENV_PATH,
        () -> {});
  }

  MarketDataClient(
      @Nullable String apiKey,
      @Nullable String baseUrl,
      @Nullable String apiVersion,
      boolean validateOnStartup,
      Function<String, @Nullable String> env,
      Path dotEnvPath,
      Runnable startupValidator) {
    this.config = Configuration.resolve(apiKey, baseUrl, apiVersion, env, dotEnvPath);
    this.rateLimits = RateLimitSnapshot.EMPTY;
    if (validateOnStartup) {
      startupValidator.run();
    }
  }

  public RateLimitSnapshot getRateLimits() {
    return rateLimits;
  }

  @Override
  public void close() {}

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
