package com.marketdata.sdk.options;

import java.util.List;
import java.util.Objects;

/**
 * Response shape for {@code GET /v1/options/chain/{underlying}/} — every option contract on the
 * underlying that matches the request's filter set. The wire-format and per-row schema match the
 * {@code quotes} endpoint exactly, so each row is decoded into the shared {@link OptionQuote}
 * record; what differs is the volume of rows (typically many) and the filter parameters available
 * via {@link OptionsChainRequest}.
 *
 * @param chain matching contracts in the order the API delivered them. Immutable; never {@code
 *     null}. Empty when the response is the {@code "s":"no_data"} envelope.
 */
public record OptionsChain(List<OptionQuote> chain) {

  public OptionsChain {
    Objects.requireNonNull(chain, "chain");
    chain = List.copyOf(chain);
  }
}
