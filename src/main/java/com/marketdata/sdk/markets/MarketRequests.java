package com.marketdata.sdk.markets;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Shared request-builder validation for the market endpoints. */
final class MarketRequests {

  private MarketRequests() {}

  /**
   * Validates the historical-window parameters: {@code date} is a single-point lookup incompatible
   * with any ranging parameter; {@code countback} is an alternative to {@code from} for the left
   * edge (the backend ignores countback when from is present — we reject the combination instead of
   * silently dropping one side); {@code countback} must be positive; and {@code from} must not be
   * after {@code to}.
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
}
