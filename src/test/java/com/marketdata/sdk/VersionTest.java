package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {

  // ---------- resolve: covers all 4 branch outcomes of the `!= null && !isBlank()` chain
  // ----------

  @Test
  void resolveReturnsDetectedVersionWhenPresent() {
    assertThat(Version.resolve("1.2.3")).isEqualTo("1.2.3");
  }

  @Test
  void resolveFallsBackWhenDetectedIsNull() {
    assertThat(Version.resolve(null)).isEqualTo(Version.FALLBACK);
  }

  @Test
  void resolveFallsBackWhenDetectedIsEmpty() {
    assertThat(Version.resolve("")).isEqualTo(Version.FALLBACK);
  }

  @Test
  void resolveFallsBackWhenDetectedIsBlank() {
    // Exercises the second condition independently (`!isBlank()` evaluated `false` on whitespace).
    assertThat(Version.resolve("   ")).isEqualTo(Version.FALLBACK);
  }

  // ---------- current: lives at the package boundary; only asserts the contract ----------

  @Test
  void currentNeverReturnsNullOrBlank() {
    // From class files in tests, the manifest has no Implementation-Version so current()
    // exercises the fallback path. From a published JAR it would return the manifest value.
    // Either way the contract holds.
    String v = Version.current();
    assertThat(v).isNotNull().isNotBlank();
  }
}
