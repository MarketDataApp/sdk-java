package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockQuote;
import java.util.List;

/**
 * Response for {@code stocks.quote} and {@code stocks.quotes}: {@link #values()} is the quote rows
 * (one for the single-symbol form, one per symbol for the batch). Construct only through the
 * resource façade.
 */
public final class StockQuotesResponse extends AbstractMarketDataResponse<List<StockQuote>> {

  StockQuotesResponse(List<StockQuote> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
