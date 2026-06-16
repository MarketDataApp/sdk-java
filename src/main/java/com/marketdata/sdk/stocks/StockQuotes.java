package com.marketdata.sdk.stocks;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of the stock quote endpoints. The single-symbol form ({@code
 * /stocks/quotes/{symbol}/}) and the multi-symbol batch ({@code /stocks/quotes/?symbols=A,B,C})
 * share this shape — the batch returns one {@link StockQuote} row per symbol in a single response
 * (the backend accepts a comma list in one request, so no fan-out is needed).
 *
 * @param quotes the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record StockQuotes(List<StockQuote> quotes) {

  public StockQuotes {
    Objects.requireNonNull(quotes, "quotes");
    quotes = List.copyOf(quotes);
  }
}
