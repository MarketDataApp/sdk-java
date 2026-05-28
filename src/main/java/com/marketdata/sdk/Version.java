package com.marketdata.sdk;

import org.jspecify.annotations.Nullable;

final class Version {

  static final String FALLBACK = "0.0.0-dev";

  static String sdkVersion() {
    Package pkg = Version.class.getPackage();
    return resolve(pkg == null ? null : pkg.getImplementationVersion());
  }

  static String resolve(@Nullable String rawVersion) {
    return (rawVersion == null || rawVersion.isBlank()) ? FALLBACK : rawVersion;
  }

  private Version() {}
}
