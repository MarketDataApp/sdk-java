package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoModeTest {

  @Test
  void is_demo_when_api_key_is_null() {
    Configuration config = configWithApiKey(null);

    assertThat(DemoMode.isDemo(config)).isTrue();
  }

  @Test
  void is_demo_when_api_key_is_empty() {
    Configuration config = configWithApiKey("");

    assertThat(DemoMode.isDemo(config)).isTrue();
  }

  @Test
  void is_demo_when_api_key_is_blank() {
    Configuration config = configWithApiKey("   ");

    assertThat(DemoMode.isDemo(config)).isTrue();
  }

  @Test
  void is_not_demo_when_api_key_present() {
    Configuration config = configWithApiKey("real-token-YKT0");

    assertThat(DemoMode.isDemo(config)).isFalse();
  }

  private static Configuration configWithApiKey(String apiKey) {
    return new Configuration(
        apiKey, Configuration.DEFAULT_BASE_URL, Configuration.DEFAULT_API_VERSION, null, null);
  }
}
