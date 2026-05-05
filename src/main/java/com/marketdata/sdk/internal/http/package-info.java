/**
 * Internal HTTP transport layer. Reusable across every endpoint in the SDK — handles URL
 * construction, auth headers, the global concurrency semaphore, response decoding, rate-limit
 * header parsing, and the mapping of HTTP status codes to {@link
 * com.marketdata.sdk.exception.MarketDataException} subtypes.
 */
@NullMarked
package com.marketdata.sdk.internal.http;

import org.jspecify.annotations.NullMarked;
