package com.marketdata.sdk.utilities;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Account-level info returned by {@code GET /v1/user/}.
 *
 * <p>Unlike most SDK responses, this endpoint emits a flat JSON object with kebab-case keys instead
 * of the parallel-arrays wire format used elsewhere. The {@link JsonProperty} annotations on the
 * canonical-constructor parameters are what let Jackson's record support map the kebab-case keys to
 * the camelCase record components — no custom deserializer needed.
 *
 * <p>Note that {@link #requestsRemaining} and {@link #requestsLimit} duplicate information that the
 * SDK also reads from response headers ({@code x-api-ratelimit-*}) on every request and exposes via
 * {@code MarketDataClient.getRateLimits()}. They are returned here as a snapshot at the moment
 * {@code /user/} was called — useful for an explicit one-shot quota query at startup (see SDK
 * requirements §8.1).
 *
 * @param requestsRemaining requests left in the current quota window
 * @param requestsLimit total request quota for the current window
 * @param optionsDataPermissions human-readable description of the account's options-data
 *     permissions; empty string for accounts with real-time OPRA access, otherwise a string like
 *     {@code "OPRA data delayed 15 minutes"}
 */
public record UserInfo(
    @JsonProperty("x-ratelimit-requests-remaining") long requestsRemaining,
    @JsonProperty("x-ratelimit-requests-limit") long requestsLimit,
    @JsonProperty("x-options-data-permissions") String optionsDataPermissions) {}
