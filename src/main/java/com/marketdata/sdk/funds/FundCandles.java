package com.marketdata.sdk.funds;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of {@code GET /v1/funds/candles/{resolution}/{symbol}/} — the OHLC series for one
 * fund, one {@link FundCandle} per bar in the order the API delivered them.
 *
 * @param candles the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record FundCandles(List<FundCandle> candles) {

  public FundCandles {
    Objects.requireNonNull(candles, "candles");
    candles = List.copyOf(candles);
  }
}
