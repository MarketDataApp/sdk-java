package com.marketdata.sdk;

import com.marketdata.sdk.funds.FundCandle;
import java.util.List;

/**
 * Response for {@code funds.candles}: {@link #values()} is the OHLC rows. Construct only through
 * the resource façade.
 */
public final class FundCandlesResponse extends AbstractMarketDataResponse<List<FundCandle>> {

  FundCandlesResponse(List<FundCandle> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
