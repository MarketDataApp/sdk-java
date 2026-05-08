package com.marketdata.sdk;

/**
 * Reads the SDK's version from the JAR manifest's {@code Implementation-Version} attribute (SDK
 * requirements §15: "version must be automatically detected from package metadata").
 *
 * <p>Falls back to {@code "0.0.0-dev"} when the class is not loaded from a JAR (e.g. running tests
 * from class files).
 */
final class Version {

  private static final String FALLBACK = "0.0.0-dev";

  private Version() {}

  public static String current() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version != null && !version.isBlank() ? version : FALLBACK;
  }
}
