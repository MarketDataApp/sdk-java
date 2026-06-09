package com.marketdata.sdk.stocks;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of {@code GET /v1/stocks/earnings/{symbol}/} — one {@link StockEarning} row per
 * report, newest last (the order the API delivers them).
 *
 * @param earnings the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record StockEarnings(List<StockEarning> earnings) {

  public StockEarnings {
    Objects.requireNonNull(earnings, "earnings");
    earnings = List.copyOf(earnings);
  }
}
