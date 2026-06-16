package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockCandle;
import java.util.List;

/**
 * Response for {@code stocks.candles}: {@link #values()} is the OHLCV rows. Construct only through
 * the resource façade.
 */
public final class StockCandlesResponse extends AbstractMarketDataResponse<List<StockCandle>> {

  StockCandlesResponse(List<StockCandle> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
