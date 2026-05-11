package com.marketdata.sdk;

/**
 * Names of the {@code MARKETDATA_*} environment variables consulted by the SDK. Mirrors SDK
 * requirements §4.
 */
final class EnvVars {

  public static final String TOKEN = "MARKETDATA_TOKEN";
  public static final String BASE_URL = "MARKETDATA_BASE_URL";
  public static final String API_VERSION = "MARKETDATA_API_VERSION";
  public static final String LOGGING_LEVEL = "MARKETDATA_LOGGING_LEVEL";
  public static final String OUTPUT_FORMAT = "MARKETDATA_OUTPUT_FORMAT";
  public static final String DATE_FORMAT = "MARKETDATA_DATE_FORMAT";
  public static final String COLUMNS = "MARKETDATA_COLUMNS";
  public static final String ADD_HEADERS = "MARKETDATA_ADD_HEADERS";
  public static final String USE_HUMAN_READABLE = "MARKETDATA_USE_HUMAN_READABLE";
  public static final String MODE = "MARKETDATA_MODE";

  private EnvVars() {}
}
