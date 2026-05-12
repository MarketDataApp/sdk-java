package com.marketdata.sdk.markets;

import java.util.List;

/**
 * Result of a {@code /v1/markets/status/} call: one {@link DailyStatus} per requested date, in
 * chronological order.
 *
 * <p>The wire format the API returns is a compressed parallel-arrays JSON payload (per SDK
 * requirements §11.1); the SDK expands it into this idiomatic typed shape via a custom Jackson
 * deserializer registered programmatically by the transport (ADR-005, ADR-007).
 *
 * <p>An empty {@code days} list means the API responded with no data — either an HTTP 404 with
 * {@code {"s":"no_data"}} or an unsupported country (currently only {@code US} returns data).
 *
 * @param days the per-day market status, never {@code null}; empty when the API has no data
 */
public record MarketStatus(List<DailyStatus> days) {

  public boolean isEmpty() {
    return days.isEmpty();
  }
}
