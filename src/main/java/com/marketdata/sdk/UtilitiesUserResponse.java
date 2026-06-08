package com.marketdata.sdk;

import com.marketdata.sdk.utilities.User;

/**
 * Response for {@code utilities.user}: {@link #values()} is the caller's quota / permissions
 * snapshot. Construct only through the resource façade.
 */
public final class UtilitiesUserResponse extends AbstractMarketDataResponse<User> {

  UtilitiesUserResponse(User values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
