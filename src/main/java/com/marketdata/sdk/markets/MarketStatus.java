package com.marketdata.sdk.markets;

import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;

/**
 * The market status of a single calendar day — one row of {@link MarketStatuses}. {@code date} is
 * midnight market-time ({@code America/New_York}); {@code status} is {@code "open"} or {@code
 * "closed"}.
 *
 * <p>Every field is a nullable boxed type so the {@code columns} universal parameter can project
 * the response to a subset (an unrequested column decodes to {@code null}). Additionally, the
 * backend itself emits a {@code null} status <em>cell</em> for days outside its holiday-calendar
 * coverage — so a {@code null} {@code status} on a row whose column you requested means "calendar
 * has no answer for this day", not a decode failure. The deserializer stays strict about
 * <em>requested</em> columns — a required column asked for but omitted by the API surfaces as a
 * {@code ParseError} (Option A), never a silent null.
 *
 * @param date the calendar day ({@code date} on the wire).
 * @param status {@code "open"} / {@code "closed"} ({@code status} on the wire), or {@code null}
 *     outside the calendar's coverage.
 */
public record MarketStatus(@Nullable ZonedDateTime date, @Nullable String status) {

  /** Whether this day is an open market day ({@code status == "open"}). */
  public boolean isOpen() {
    return "open".equals(status);
  }

  /** Whether this day is a closed market day ({@code status == "closed"}). */
  public boolean isClosed() {
    return "closed".equals(status);
  }
}
