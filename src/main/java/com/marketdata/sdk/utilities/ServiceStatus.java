package com.marketdata.sdk.utilities;

import java.time.Instant;

/**
 * Health of a single API service, as reported by {@code GET /status/}.
 *
 * <p>The {@code /status/} endpoint returns the parallel-arrays wire format used across the Market
 * Data API; the SDK's deserializer zips those arrays into a record per service so consumers iterate
 * naturally instead of indexing into six parallel collections.
 *
 * @param service service path the API exposes, e.g. {@code "/v1/stocks/quotes/"}.
 * @param status status label as a string (today: {@code "online"} or {@code "offline"}; left
 *     stringly-typed so a future tier the server adds doesn't break this enum).
 * @param online convenience boolean parallel to {@link #status} — server-supplied, not derived.
 * @param uptimePct30d uptime fraction in the last 30 days, in the range {@code [0.0, 1.0]}.
 * @param uptimePct90d uptime fraction in the last 90 days, in the range {@code [0.0, 1.0]}.
 * @param updated when this entry was last refreshed server-side.
 */
public record ServiceStatus(
    String service,
    String status,
    boolean online,
    double uptimePct30d,
    double uptimePct90d,
    Instant updated) {}
