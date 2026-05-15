package com.marketdata.sdk;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

record Configuration(
    @Nullable String apiKey,
    String baseUrl,
    String apiVersion,
    @Nullable String loggingLevel,
    @Nullable String dateFormat) {

  static final String DEFAULT_BASE_URL = "https://api.marketdata.app";
  static final String DEFAULT_API_VERSION = "v1";
  static final Path DEFAULT_DOTENV_PATH = Path.of(".env");

  static Configuration resolve(
      @Nullable String explicitApiKey,
      @Nullable String explicitBaseUrl,
      @Nullable String explicitApiVersion,
      Function<String, @Nullable String> env,
      Path dotEnvPath) {
    Map<String, String> dotEnv = DotEnvLoader.load(dotEnvPath);
    String apiKey = pickFirst(explicitApiKey, env.apply(EnvVars.TOKEN), dotEnv.get(EnvVars.TOKEN));
    String baseUrl =
        pickFirstOrDefault(
            DEFAULT_BASE_URL,
            explicitBaseUrl,
            env.apply(EnvVars.BASE_URL),
            dotEnv.get(EnvVars.BASE_URL));
    String apiVersion =
        pickFirstOrDefault(
            DEFAULT_API_VERSION,
            explicitApiVersion,
            env.apply(EnvVars.API_VERSION),
            dotEnv.get(EnvVars.API_VERSION));
    String loggingLevel =
        pickFirst(env.apply(EnvVars.LOGGING_LEVEL), dotEnv.get(EnvVars.LOGGING_LEVEL));
    String dateFormat = pickFirst(env.apply(EnvVars.DATE_FORMAT), dotEnv.get(EnvVars.DATE_FORMAT));
    return new Configuration(apiKey, baseUrl, apiVersion, loggingLevel, dateFormat);
  }

  private static @Nullable String pickFirst(@Nullable String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  private static String pickFirstOrDefault(String fallback, @Nullable String... candidates) {
    String picked = pickFirst(candidates);
    return picked != null ? picked : fallback;
  }
}
