package com.marketdata.sdk;

import java.time.Instant;

/**
 * Snapshot of the API rate-limit state, parsed from the {@code x-api-ratelimit-*} response headers.
 *
 * <p>Per SDK requirements §8, this is a client-level snapshot and is non-deterministic under
 * concurrent use; per-request metadata is attached to each response separately.
 *
 * @param limit total credits available in the current window
 * @param remaining credits left in the current window
 * @param reset instant at which {@code remaining} resets to {@code limit}
 * @param consumed credits consumed by the most recent request
 */
public record RateLimits(long limit, long remaining, Instant reset, long consumed) {}
