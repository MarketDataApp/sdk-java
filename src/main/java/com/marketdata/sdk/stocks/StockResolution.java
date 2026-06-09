package com.marketdata.sdk.stocks;

import java.util.Objects;

/**
 * Candle resolution for {@code GET /v1/stocks/candles/{resolution}/{symbol}/}. A value type rather
 * than an enum: the API accepts an open-ended family of resolutions (any minute count, any multiple
 * of hours/days/weeks/months/years), so the meaningful values cannot be enumerated up front.
 *
 * <p>Use the factories for the common shapes — {@link #minutes(int)}, {@link #hours(int)}, {@link
 * #days(int)}, {@link #weeks(int)}, {@link #months(int)}, {@link #years(int)} — or the {@link
 * #DAILY}/{@link #WEEKLY}/{@link #MONTHLY}/{@link #YEARLY} constants for the bare forms. {@link
 * #of(String)} passes an arbitrary wire token through verbatim for resolutions the factories don't
 * model.
 */
public final class StockResolution {

  /** Daily candles ({@code D}). */
  public static final StockResolution DAILY = new StockResolution("D");

  /** Weekly candles ({@code W}). */
  public static final StockResolution WEEKLY = new StockResolution("W");

  /** Monthly candles ({@code M}). */
  public static final StockResolution MONTHLY = new StockResolution("M");

  /** Yearly candles ({@code Y}). */
  public static final StockResolution YEARLY = new StockResolution("Y");

  private final String wireValue;

  private StockResolution(String wireValue) {
    this.wireValue = wireValue;
  }

  /** Minutely candles: {@code n} minutes per bar (e.g. {@code 1}, {@code 5}, {@code 15}). */
  public static StockResolution minutes(int n) {
    return new StockResolution(Integer.toString(requirePositive(n, "minutes")));
  }

  /** Hourly candles: {@code n} hours per bar (e.g. {@code 1H}, {@code 4H}). */
  public static StockResolution hours(int n) {
    return new StockResolution(requirePositive(n, "hours") + "H");
  }

  /** Daily candles: {@code n} days per bar (e.g. {@code 1D}, {@code 2D}). */
  public static StockResolution days(int n) {
    return new StockResolution(requirePositive(n, "days") + "D");
  }

  /** Weekly candles: {@code n} weeks per bar. */
  public static StockResolution weeks(int n) {
    return new StockResolution(requirePositive(n, "weeks") + "W");
  }

  /** Monthly candles: {@code n} months per bar. */
  public static StockResolution months(int n) {
    return new StockResolution(requirePositive(n, "months") + "M");
  }

  /** Yearly candles: {@code n} years per bar. */
  public static StockResolution years(int n) {
    return new StockResolution(requirePositive(n, "years") + "Y");
  }

  /** An arbitrary resolution token, passed to the API verbatim. Must be non-blank. */
  public static StockResolution of(String wireValue) {
    Objects.requireNonNull(wireValue, "wireValue");
    if (wireValue.isBlank()) {
      throw new IllegalArgumentException("resolution must be non-blank");
    }
    return new StockResolution(wireValue);
  }

  private static int requirePositive(int n, String name) {
    if (n <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return n;
  }

  /** The value placed in the {@code {resolution}} path segment. */
  public String wireValue() {
    return wireValue;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof StockResolution other && wireValue.equals(other.wireValue);
  }

  @Override
  public int hashCode() {
    return wireValue.hashCode();
  }

  @Override
  public String toString() {
    return "StockResolution[" + wireValue + "]";
  }
}
