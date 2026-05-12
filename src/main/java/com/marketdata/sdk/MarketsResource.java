package com.marketdata.sdk;

import com.marketdata.sdk.markets.MarketStatus;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Façade for the {@code /v1/markets/*} endpoint group.
 *
 * <p>Per ADR-006 every endpoint exposes a sync and an {@code …Async} variant. Both share the same
 * request-building code; the sync forms are thin wrappers around the async path.
 *
 * <p>Per ADR-007 this resource lives in the SDK root package alongside the infra it depends on
 * ({@link HttpTransport}, {@link RequestSpec}). Its constructor is package-private so only {@link
 * MarketDataClient} can build one — consumers reach it via {@link MarketDataClient#markets()}.
 *
 * <p>Currently only {@code /v1/markets/status/} is implemented. Future markets-related endpoints
 * (none planned today) would land here.
 */
public final class MarketsResource {

  private static final String STATUS_PATH = "markets/status";
  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private final HttpTransport transport;

  MarketsResource(HttpTransport transport) {
    this.transport = transport;
  }

  /**
   * Today's market status for US exchanges. Equivalent to {@code GET /v1/markets/status/}.
   *
   * <p>Sync. The async sibling is {@link #statusAsync()}.
   */
  public MarketStatus status() {
    return transport.executeSync(RequestSpec.get(STATUS_PATH).build(), MarketStatus.class);
  }

  /** Async variant of {@link #status()}. */
  public CompletableFuture<MarketStatus> statusAsync() {
    return transport.executeAsync(RequestSpec.get(STATUS_PATH).build(), MarketStatus.class);
  }

  /**
   * Market status for a single trading day. Equivalent to {@code GET
   * /v1/markets/status/?date=YYYY-MM-DD}.
   *
   * @param date the trading day to look up; sent in ISO-8601 format
   */
  public MarketStatus status(LocalDate date) {
    return transport.executeSync(forDate(date), MarketStatus.class);
  }

  /** Async variant of {@link #status(LocalDate)}. */
  public CompletableFuture<MarketStatus> statusAsync(LocalDate date) {
    return transport.executeAsync(forDate(date), MarketStatus.class);
  }

  /**
   * Market status for a closed date range. Equivalent to {@code GET
   * /v1/markets/status/?from=YYYY-MM-DD&to=YYYY-MM-DD}. Both endpoints are inclusive.
   *
   * @param from start of the range (inclusive)
   * @param to end of the range (inclusive)
   * @throws IllegalArgumentException if {@code from} is after {@code to}
   */
  public MarketStatus status(LocalDate from, LocalDate to) {
    return transport.executeSync(forRange(from, to), MarketStatus.class);
  }

  /** Async variant of {@link #status(LocalDate, LocalDate)}. */
  public CompletableFuture<MarketStatus> statusAsync(LocalDate from, LocalDate to) {
    return transport.executeAsync(forRange(from, to), MarketStatus.class);
  }

  private static RequestSpec forDate(LocalDate date) {
    return RequestSpec.get(STATUS_PATH).query("date", ISO_DATE.format(date)).build();
  }

  private static RequestSpec forRange(LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from (" + from + ") must not be after to (" + to + ")");
    }
    return RequestSpec.get(STATUS_PATH)
        .query("from", ISO_DATE.format(from))
        .query("to", ISO_DATE.format(to))
        .build();
  }
}
