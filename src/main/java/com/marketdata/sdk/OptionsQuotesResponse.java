package com.marketdata.sdk;

import com.marketdata.sdk.options.OptionQuote;
import java.util.List;

/**
 * Response for {@code options.quote} (and each per-symbol entry of {@code options.quotes}): {@link
 * #values()} is the option-quote rows. Construct only through the resource façade.
 */
public final class OptionsQuotesResponse extends AbstractMarketDataResponse<List<OptionQuote>> {

  OptionsQuotesResponse(List<OptionQuote> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
