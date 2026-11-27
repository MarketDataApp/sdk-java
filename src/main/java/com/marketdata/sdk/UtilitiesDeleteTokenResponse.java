package com.marketdata.sdk;

/**
 * Response for {@code utilities.deleteToken}: {@link #values()} is {@code true} once the server
 * confirms the token used to authenticate the request was revoked. Construct only through the
 * resource façade.
 */
public final class UtilitiesDeleteTokenResponse extends AbstractMarketDataResponse<Boolean> {

  UtilitiesDeleteTokenResponse(Boolean values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
