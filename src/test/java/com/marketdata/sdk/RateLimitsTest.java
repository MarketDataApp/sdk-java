package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitsTest {

  @Test
  void recordExposesAllFields() {
    Instant reset = Instant.parse("2026-05-04T12:00:00Z");
    var rl = new RateLimits(50_000L, 49_500L, reset, 1L);

    assertThat(rl.limit()).isEqualTo(50_000L);
    assertThat(rl.remaining()).isEqualTo(49_500L);
    assertThat(rl.reset()).isEqualTo(reset);
    assertThat(rl.consumed()).isEqualTo(1L);
  }
}
