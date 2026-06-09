package com.marketdata.sdk.stocks;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of {@code GET /v1/stocks/candles/{resolution}/{symbol}/} — the OHLCV series for one
 * symbol, one {@link StockCandle} per bar in the order the API delivered them.
 *
 * @param candles the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record StockCandles(List<StockCandle> candles) {

  public StockCandles {
    Objects.requireNonNull(candles, "candles");
    candles = List.copyOf(candles);
  }
}
