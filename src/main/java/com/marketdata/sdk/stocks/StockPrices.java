package com.marketdata.sdk.stocks;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of {@code GET /v1/stocks/prices/?symbols=A,B,C} — one {@link StockPrice} row per
 * requested symbol in a single response.
 *
 * @param prices the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record StockPrices(List<StockPrice> prices) {

  public StockPrices {
    Objects.requireNonNull(prices, "prices");
    prices = List.copyOf(prices);
  }
}
