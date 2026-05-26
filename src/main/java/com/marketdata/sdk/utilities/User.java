package com.marketdata.sdk.utilities;

/**
 * Response shape for {@code GET /user/} — the caller's current quota and data-tier permissions.
 *
 * <p>The numeric fields duplicate information that arrives on every response via the {@code
 * x-api-ratelimit-*} headers (see {@link com.marketdata.sdk.RateLimitSnapshot}); the dedicated
 * endpoint is mostly useful for a quota check that doesn't consume a request against the more
 * expensive business endpoints.
 *
 * @param requestsRemaining how many requests the caller can still make in the current quota window.
 * @param requestsLimit total requests allowed in the current quota window.
 * @param optionsDataPermissions data-tier label for options — empty string for real-time access,
 *     {@code "OPRA data delayed 15 minutes"} otherwise.
 */
public record User(int requestsRemaining, int requestsLimit, String optionsDataPermissions) {}
