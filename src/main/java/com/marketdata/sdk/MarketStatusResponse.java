package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatus;
import java.util.List;

/**
 * Response for {@code markets.status}: {@link #values()} is one row per calendar day. Construct
 * only through the resource façade.
 */
public final class MarketStatusResponse extends AbstractMarketDataResponse<List<MarketStatus>> {

  MarketStatusResponse(List<MarketStatus> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
