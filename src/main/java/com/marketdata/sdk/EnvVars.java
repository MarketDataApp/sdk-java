package com.marketdata.sdk;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class EnvVars {

  static final String TOKEN = "MARKETDATA_TOKEN";
  static final String BASE_URL = "MARKETDATA_BASE_URL";
  static final String API_VERSION = "MARKETDATA_API_VERSION";
  static final String LOGGING_LEVEL = "MARKETDATA_LOGGING_LEVEL";
  static final String DATE_FORMAT = "MARKETDATA_DATE_FORMAT";

  static Function<String, @Nullable String> systemLookup() {
    return System::getenv;
  }

  private EnvVars() {}
}
