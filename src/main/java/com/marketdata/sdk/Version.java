package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Reads the SDK's version from the JAR manifest's {@code Implementation-Version} attribute (SDK
 * requirements §15: "version must be automatically detected from package metadata").
 *
 * <p>Falls back to {@code "0.0.0-dev"} when the class is not loaded from a JAR (e.g. running tests
 * from class files).
 */
final class Version {

  static final String FALLBACK = "0.0.0-dev";

  private Version() {}

  public static String current() {
    return resolve(Version.class.getPackage().getImplementationVersion());
  }

  // Extracted so tests can exercise both the present-version and fallback branches without
  // requiring the SDK to be loaded from an actual JAR with an Implementation-Version manifest.
  static String resolve(@Nullable String detected) {
    return detected != null && !detected.isBlank() ? detected : FALLBACK;
  }
}
