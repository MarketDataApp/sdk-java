package com.marketdata.sdk;

import java.util.Map;

/**
 * Response for {@code utilities.headers}: {@link #values()} is the request headers the server
 * received (sensitive values redacted server-side), as a map. Construct only through the resource
 * façade.
 */
public final class UtilitiesHeadersResponse
    extends AbstractMarketDataResponse<Map<String, String>> {

  UtilitiesHeadersResponse(
      Map<String, String> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
