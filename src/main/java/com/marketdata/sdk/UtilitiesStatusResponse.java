package com.marketdata.sdk;

import com.marketdata.sdk.utilities.ServiceStatus;
import java.util.List;

/**
 * Response for {@code utilities.status}: {@link #values()} is the per-service health snapshot.
 * Construct only through the resource façade (package-private constructor, ADR-007).
 */
public final class UtilitiesStatusResponse extends AbstractMarketDataResponse<List<ServiceStatus>> {

  UtilitiesStatusResponse(
      List<ServiceStatus> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
