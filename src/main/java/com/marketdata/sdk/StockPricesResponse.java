package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockPrice;
import java.util.List;

/**
 * Response for {@code stocks.prices}: {@link #values()} is one {@link StockPrice} row per requested
 * symbol. Construct only through the resource façade.
 */
public final class StockPricesResponse extends AbstractMarketDataResponse<List<StockPrice>> {

  StockPricesResponse(List<StockPrice> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
