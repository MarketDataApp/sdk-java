package com.marketdata.sdk;

import java.net.URI;
import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/**
 * Format-agnostic envelope returned by {@link HttpTransport} to resources.
 *
 * <p>The transport's job ends here: it confirms the response was a success-shaped HTTP status
 * (200/203/404 per the API contract), maps real errors (4xx/5xx) to typed exceptions, and hands the
 * raw bytes back. Whether the body is JSON, CSV, or HTML is the resource's decision — the transport
 * does not parse it.
 *
 * @param body raw response bytes, exactly as received from the wire. May be empty.
 * @param statusCode the HTTP status code (one of 200, 203, 404).
 * @param requestId server-provided request id (e.g. Cloudflare {@code cf-ray}), {@code null} when
 *     the response did not carry one. Useful when the resource's own parser fails and needs to
 *     build an {@code ErrorContext}.
 * @param headers full response headers, in case a resource needs to read content-type, encoding,
 *     pagination, etc.
 * @param url the absolute URL the response came from. Useful for error contexts.
 */
record HttpResponseEnvelope(
    byte[] body, int statusCode, @Nullable String requestId, HttpHeaders headers, URI url) {}
