package com.marketdata.sdk;

final class DemoMode {

  static boolean isDemo(Configuration config) {
    String apiKey = config.apiKey();
    return apiKey == null || apiKey.isBlank();
  }

  private DemoMode() {}
}
