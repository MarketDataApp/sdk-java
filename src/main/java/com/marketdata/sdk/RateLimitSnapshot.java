package com.marketdata.sdk;

import java.time.Instant;

public record RateLimitSnapshot(int limit, int remaining, Instant reset, int consumed) {

  public static final RateLimitSnapshot EMPTY = new RateLimitSnapshot(0, 0, Instant.EPOCH, 0);
}
