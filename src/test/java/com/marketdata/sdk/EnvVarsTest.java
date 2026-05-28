package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EnvVarsTest {

  @Test
  void systemLookupReturnsNullForKeysOutsideTheAllowlist() {
    // The classic risk: a Function<String, String> handed to other code could be invoked with
    // arbitrary keys and silently read AWS_SECRET_ACCESS_KEY or PATH from the process env.
    // The lookup must refuse anything outside the MARKETDATA_* set, even if the real env has it.
    Function<String, String> lookup = EnvVars.systemLookup();

    assertThat(lookup.apply("PATH")).isNull(); // PATH is virtually always set in real envs
    assertThat(lookup.apply("HOME")).isNull();
    assertThat(lookup.apply("FOOBAR_DOES_NOT_EXIST")).isNull();
    assertThat(lookup.apply("")).isNull();
  }

  @Test
  void systemLookupAllowsExactlyTheDeclaredMarketdataKeys() {
    // Regression guard: if a new MARKETDATA_* constant is added to EnvVars, it must also be
    // wired into ALLOWED_KEYS, or systemLookup() silently swallows reads of the new key.
    assertThat(EnvVars.ALLOWED_KEYS)
        .containsExactlyInAnyOrder(
            EnvVars.TOKEN,
            EnvVars.BASE_URL,
            EnvVars.API_VERSION,
            EnvVars.LOGGING_LEVEL,
            EnvVars.DATE_FORMAT);
  }

  @Test
  void systemLookupForAllowedKeyMatchesSystemGetenv() {
    // Best-effort sanity: for an allowed key the lookup must mirror System.getenv. We can't
    // force a MARKETDATA_* var to be set on the test JVM, so just assert that whatever the
    // process has (likely null) is what the lookup returns — i.e., no extra filtering or
    // transformation is applied on top of System.getenv for permitted keys.
    Function<String, String> lookup = EnvVars.systemLookup();

    assertThat(lookup.apply(EnvVars.TOKEN)).isEqualTo(System.getenv(EnvVars.TOKEN));
    assertThat(lookup.apply(EnvVars.BASE_URL)).isEqualTo(System.getenv(EnvVars.BASE_URL));
  }
}
