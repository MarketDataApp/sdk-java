package com.marketdata.sdk.options;

import java.util.List;
import java.util.Objects;

/**
 * Response shape for {@code GET /v1/options/quotes/{optionSymbol}/} — the end-of-day option quote
 * (or historical series) for a single contract. The wire-format is the standard parallel-arrays
 * envelope; the SDK lifts each row into an {@link OptionQuote}.
 *
 * <p>This wrapper is also the per-symbol response type produced by the multi-symbol {@code
 * options.quotes(...)} convenience: each symbol's parallel-arrays body becomes one {@code
 * OptionsQuotes} (with typically one row) under its symbol key in the returned {@code Map}.
 *
 * @param quotes the decoded rows in the order the API delivered them. Immutable; never {@code
 *     null}. Empty when the response is the {@code "s":"no_data"} envelope.
 */
public record OptionsQuotes(List<OptionQuote> quotes) {

  public OptionsQuotes {
    Objects.requireNonNull(quotes, "quotes");
    quotes = List.copyOf(quotes);
  }
}
