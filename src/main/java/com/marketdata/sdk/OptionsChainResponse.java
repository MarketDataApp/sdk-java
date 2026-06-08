package com.marketdata.sdk;

import com.marketdata.sdk.options.OptionQuote;
import java.util.List;

/**
 * Response for {@code options.chain}: {@link #values()} is the chain's option rows. Construct only
 * through the resource façade (package-private constructor, ADR-007).
 */
public final class OptionsChainResponse extends AbstractMarketDataResponse<List<OptionQuote>> {

  OptionsChainResponse(List<OptionQuote> values, HttpResponseEnvelope envelope, Format format) {
    super(values, envelope, format);
  }
}
