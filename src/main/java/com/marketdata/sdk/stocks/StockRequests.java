package com.marketdata.sdk.stocks;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Shared request-builder validation for the stock endpoints. */
final class StockRequests {

  private StockRequests() {}

  /**
   * Validates the historical-window parameters shared by candles/news/earnings: {@code date} is a
   * single-point lookup incompatible with any ranging parameter; {@code countback} is an
   * alternative to {@code from} for the left edge (per the API: "if you use from, countback is not
   * required"), so the two cannot be combined; {@code countback} must be positive; and {@code from}
   * must not be after {@code to}.
   */
  static void validateWindow(
      @Nullable LocalDate date,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable Integer countback) {
    if (date != null && (from != null || to != null || countback != null)) {
      throw new IllegalArgumentException("date and from/to/countback are mutually exclusive");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
    if (countback != null) {
      if (countback <= 0) {
        throw new IllegalArgumentException("countback must be positive");
      }
      if (from != null) {
        throw new IllegalArgumentException(
            "countback and from are mutually exclusive; pair countback with to");
      }
    }
  }

  static String requireNonEmpty(String value, String name) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " must be non-empty");
    }
    return value;
  }
}
