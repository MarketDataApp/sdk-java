package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitSnapshotTest {

  @Test
  void exposes_all_fields() {
    Instant reset = Instant.parse("2026-05-15T12:00:00Z");

    RateLimitSnapshot snapshot = new RateLimitSnapshot(1000, 750, reset, 250);

    assertThat(snapshot.limit()).isEqualTo(1000);
    assertThat(snapshot.remaining()).isEqualTo(750);
    assertThat(snapshot.reset()).isEqualTo(reset);
    assertThat(snapshot.consumed()).isEqualTo(250);
  }

  @Test
  void records_with_same_values_are_equal() {
    Instant reset = Instant.parse("2026-05-15T12:00:00Z");

    RateLimitSnapshot a = new RateLimitSnapshot(100, 50, reset, 50);
    RateLimitSnapshot b = new RateLimitSnapshot(100, 50, reset, 50);

    assertThat(a).isEqualTo(b);
    assertThat(a).hasSameHashCodeAs(b);
  }
}
