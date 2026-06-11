package com.marketdata.sdk.funds;

import java.util.Objects;

/**
 * Candle resolution for {@code GET /v1/funds/candles/{resolution}/{symbol}/}. A value type rather
 * than an enum: the API accepts an open-ended family of resolutions (any multiple of
 * days/weeks/months/years), so the meaningful values cannot be enumerated up front.
 *
 * <p>Use the factories for the common shapes — {@link #days(int)}, {@link #weeks(int)}, {@link
 * #months(int)}, {@link #years(int)} — or the {@link #DAILY}/{@link #WEEKLY}/{@link
 * #MONTHLY}/{@link #YEARLY} constants for the bare forms. {@link #of(String)} passes an arbitrary
 * wire token through verbatim for resolutions the factories don't model.
 *
 * <p>Unlike stocks, funds have no intraday resolutions: the backend rejects minutely/hourly tokens
 * with {@code "Intraday resolutions are not available for fund candles."} — so this type offers no
 * {@code minutes}/{@code hours} factories and the resource never auto-chunks date ranges (§12 only
 * applies to intraday candles).
 */
public final class FundResolution {

  /** Daily candles ({@code D}). */
  public static final FundResolution DAILY = new FundResolution("D");

  /** Weekly candles ({@code W}). */
  public static final FundResolution WEEKLY = new FundResolution("W");

  /** Monthly candles ({@code M}). */
  public static final FundResolution MONTHLY = new FundResolution("M");

  /** Yearly candles ({@code Y}). */
  public static final FundResolution YEARLY = new FundResolution("Y");

  private final String wireValue;

  private FundResolution(String wireValue) {
    this.wireValue = wireValue;
  }

  /** Daily candles: {@code n} days per bar (e.g. {@code 1D}, {@code 2D}). */
  public static FundResolution days(int n) {
    return new FundResolution(requirePositive(n, "days") + "D");
  }

  /** Weekly candles: {@code n} weeks per bar. */
  public static FundResolution weeks(int n) {
    return new FundResolution(requirePositive(n, "weeks") + "W");
  }

  /** Monthly candles: {@code n} months per bar. */
  public static FundResolution months(int n) {
    return new FundResolution(requirePositive(n, "months") + "M");
  }

  /** Yearly candles: {@code n} years per bar. */
  public static FundResolution years(int n) {
    return new FundResolution(requirePositive(n, "years") + "Y");
  }

  /** An arbitrary resolution token, passed to the API verbatim. Must be non-blank. */
  public static FundResolution of(String wireValue) {
    Objects.requireNonNull(wireValue, "wireValue");
    if (wireValue.isBlank()) {
      throw new IllegalArgumentException("resolution must be non-blank");
    }
    return new FundResolution(wireValue);
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
    return o instanceof FundResolution other && wireValue.equals(other.wireValue);
  }

  @Override
  public int hashCode() {
    return wireValue.hashCode();
  }

  @Override
  public String toString() {
    return "FundResolution[" + wireValue + "]";
  }
}
