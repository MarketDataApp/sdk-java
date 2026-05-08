package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestSpecTest {

  @Test
  void buildPreservesPathAndOmitsNullQueryParams() {
    // Covers both branches of `if (value != null)` in Builder.query: the null branch is
    // exercised by .query("ignored", null), the non-null branch by .query("date", "2024-05-01").
    RequestSpec spec =
        RequestSpec.get("markets/status")
            .query("date", "2024-05-01")
            .query("ignored", null)
            .query("from", "2024-01-01")
            .build();

    assertThat(spec.path()).isEqualTo("markets/status");
    assertThat(spec.queryParams())
        .containsExactly(
            java.util.Map.entry("date", "2024-05-01"), java.util.Map.entry("from", "2024-01-01"));
    assertThat(spec.queryParams()).doesNotContainKey("ignored");
  }

  @Test
  void buildWithNoQueryParamsProducesEmptyMap() {
    RequestSpec spec = RequestSpec.get("markets/status").build();

    assertThat(spec.path()).isEqualTo("markets/status");
    assertThat(spec.queryParams()).isEmpty();
  }

  @Test
  void queryParamsAreImmutable() {
    RequestSpec spec = RequestSpec.get("markets/status").query("date", "2024-05-01").build();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> spec.queryParams().put("hacked", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void queryConvertsNonStringValuesViaToString() {
    // value.toString() is called when value is non-null. Numbers, enums, etc. should serialise
    // through their toString().
    RequestSpec spec =
        RequestSpec.get("markets/candles")
            .query("countback", 5)
            .query("limit", Long.valueOf(100L))
            .build();

    assertThat(spec.queryParams()).containsEntry("countback", "5").containsEntry("limit", "100");
  }
}
