package com.marketdata.sdk;

import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class EnvVars {

  static final String TOKEN = "MARKETDATA_TOKEN";
  static final String BASE_URL = "MARKETDATA_BASE_URL";
  static final String API_VERSION = "MARKETDATA_API_VERSION";
  static final String LOGGING_LEVEL = "MARKETDATA_LOGGING_LEVEL";
  static final String DATE_FORMAT = "MARKETDATA_DATE_FORMAT";

  static final Set<String> ALLOWED_KEYS =
      Set.of(TOKEN, BASE_URL, API_VERSION, LOGGING_LEVEL, DATE_FORMAT);

  /**
   * Lookup function over the SDK-relevant environment variables. Restricts reads to {@link
   * #ALLOWED_KEYS} so the {@link Function} can be passed around safely — any other key returns
   * {@code null} without touching {@code System.getenv}. Today's only caller ({@link
   * Configuration#resolve}) already invokes with just the {@code MARKETDATA_*} keys; the
   * restriction is defensive: a future caller that accidentally tries to read {@code PATH} or
   * {@code AWS_SECRET_ACCESS_KEY} through this seam would silently get {@code null} instead of
   * leaking the value.
   */
  static Function<String, @Nullable String> systemLookup() {
    return key -> ALLOWED_KEYS.contains(key) ? System.getenv(key) : null;
  }

  private EnvVars() {}
}
