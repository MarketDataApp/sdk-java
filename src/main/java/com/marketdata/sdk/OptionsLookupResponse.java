package com.marketdata.sdk;

/**
 * Response for {@code options.lookup}: {@link #values()} is the resolved OCC option symbol (a
 * scalar). Construct only through the resource façade.
 */
public final class OptionsLookupResponse extends AbstractMarketDataResponse<String> {

  OptionsLookupResponse(String values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
