package com.marketdata.sdk;

import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class MarketDataClient implements AutoCloseable {

  private final Configuration config;
  private final HttpTransport transport;

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
    this.transport =
        new HttpTransport(
            config.baseUrl(),
            config.apiVersion(),
            "marketdata-sdk-java/" + Version.sdkVersion(),
            config.apiKey());
    if (validateOnStartup) {
      startupValidator.run();
    }
  }

  /**
   * Latest rate-limit snapshot recorded from any successful response. Returns {@link
   * RateLimitSnapshot#EMPTY} until the first rate-limit-bearing response has arrived; never null.
   */
  public RateLimitSnapshot getRateLimits() {
    RateLimitSnapshot latest = transport.getLatestRateLimits();
    return latest != null ? latest : RateLimitSnapshot.EMPTY;
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
