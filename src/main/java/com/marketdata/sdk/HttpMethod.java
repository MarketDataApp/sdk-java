package com.marketdata.sdk;

/**
 * HTTP verb a {@link RequestSpec} is sent with. Every endpoint before the {@code DELETE
 * /v1/user/token/} token-revocation endpoint was a {@code GET}; this enum exists so {@link
 * RequestSpec} and {@link HttpTransport} can carry and dispatch a second verb without
 * special-casing "the one endpoint that isn't GET".
 */
enum HttpMethod {
  GET,
  DELETE
}
