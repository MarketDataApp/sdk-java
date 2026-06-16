package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockEarning;
import java.util.List;

/**
 * Response for {@code stocks.earnings}: {@link #values()} is the earnings rows. Construct only
 * through the resource façade.
 */
public final class StockEarningsResponse extends AbstractMarketDataResponse<List<StockEarning>> {

  StockEarningsResponse(List<StockEarning> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
