package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {

  @Test
  void resolve_returns_fallback_for_null() {
    assertThat(Version.resolve(null)).isEqualTo(Version.FALLBACK);
  }

  @Test
  void resolve_returns_fallback_for_empty_string() {
    assertThat(Version.resolve("")).isEqualTo(Version.FALLBACK);
  }

  @Test
  void resolve_returns_fallback_for_blank_string() {
    assertThat(Version.resolve("   ")).isEqualTo(Version.FALLBACK);
  }

  @Test
  void resolve_returns_value_when_present() {
    assertThat(Version.resolve("1.2.3")).isEqualTo("1.2.3");
  }

  @Test
  void resolve_returns_snapshot_value_unchanged() {
    assertThat(Version.resolve("0.1.0-SNAPSHOT")).isEqualTo("0.1.0-SNAPSHOT");
  }

  @Test
  void sdk_version_returns_fallback_when_loaded_from_classpath_not_jar() {
    String version = Version.sdkVersion();

    assertThat(version).isEqualTo(Version.FALLBACK);
  }

  @Test
  void sdk_version_never_returns_null_or_blank() {
    String version = Version.sdkVersion();

    assertThat(version).isNotNull();
    assertThat(version.isBlank()).isFalse();
  }
}
