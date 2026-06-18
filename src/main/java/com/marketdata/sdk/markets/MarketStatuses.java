package com.marketdata.sdk.markets;

import java.util.List;
import java.util.Objects;

/**
 * Decoded body of {@code GET /v1/markets/status/} — one {@link MarketStatus} per calendar day in
 * the order the API delivered them.
 *
 * @param statuses the rows; immutable, never {@code null}, empty for a {@code "s":"no_data"} body.
 */
public record MarketStatuses(List<MarketStatus> statuses) {

  public MarketStatuses {
    Objects.requireNonNull(statuses, "statuses");
    statuses = List.copyOf(statuses);
  }
}
