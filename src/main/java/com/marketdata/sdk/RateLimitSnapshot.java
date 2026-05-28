package com.marketdata.sdk;

import java.time.Instant;

public record RateLimitSnapshot(int limit, int remaining, Instant reset, int consumed) {}
