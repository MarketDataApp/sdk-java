package com.marketdata.sdk.utilities;

import java.time.Instant;

/**
 * Health snapshot for a single service monitored by the API status endpoint.
 *
 * @param service path of the service being monitored, e.g. {@code "/v1/funds/candles/"}
 * @param online {@code true} if the service is currently up
 * @param status human-readable status — typically {@code "online"} or {@code "offline"}
 * @param uptimePct30d uptime fraction over the last 30 days (0.0–1.0)
 * @param uptimePct90d uptime fraction over the last 90 days (0.0–1.0)
 * @param updated when the status snapshot was last refreshed
 */
public record ServiceHealth(
    String service,
    boolean online,
    String status,
    double uptimePct30d,
    double uptimePct90d,
    Instant updated) {}
