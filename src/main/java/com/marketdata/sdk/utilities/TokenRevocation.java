package com.marketdata.sdk.utilities;

/**
 * Response shape for {@code DELETE /v1/user/token/} — confirmation that the token used to
 * authenticate the request was revoked. The API documents no fields beyond the success envelope
 * ({@code "s":"ok"}) for this endpoint, so this record carries none; {@link
 * com.marketdata.sdk.UtilitiesDeleteTokenResponse#values()} exposes the outcome as a flat {@code
 * Boolean} instead.
 */
public record TokenRevocation() {}
