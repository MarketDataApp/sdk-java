package com.marketdata.sdk.funds;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/funds/candles/{resolution}/{symbol}/}. The {@link FundResolution}
 * and the fund's ticker {@code symbol} are required; the rest bound the window.
 *
 * <p>Window rules (enforced in {@link Builder#build()}): {@code date} selects a single trading day
 * and is incompatible with {@code from}/{@code to}/{@code countback}; {@code countback} pairs with
 * {@code to} (not {@code from}) and must be positive.
 *
 * <p>There is no {@code extended} parameter: extended-hours sessions only exist on intraday
 * candles, which the funds endpoint does not serve. The funds endpoint also does not honor {@code
 * exchange}/{@code country}/{@code adjustsplits}/{@code adjustdividends}, so those are not exposed.
 */
public final class FundCandlesRequest {

  private final FundResolution resolution;
  private final String symbol;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;

  private FundCandlesRequest(Builder b) {
    this.resolution = b.resolution;
    this.symbol = b.symbol;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
  }

  /** Shortcut for {@code builder(resolution, symbol).build()}. */
  public static FundCandlesRequest of(FundResolution resolution, String symbol) {
    return builder(resolution, symbol).build();
  }

  public static Builder builder(FundResolution resolution, String symbol) {
    return new Builder(resolution, symbol);
  }

  public FundResolution resolution() {
    return resolution;
  }

  public String symbol() {
    return symbol;
  }

  public @Nullable LocalDate date() {
    return date;
  }

  public @Nullable LocalDate from() {
    return from;
  }

  public @Nullable LocalDate to() {
    return to;
  }

  public @Nullable Integer countback() {
    return countback;
  }

  public static final class Builder {
    private final FundResolution resolution;
    private final String symbol;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;

    private Builder(FundResolution resolution, String symbol) {
      this.resolution = Objects.requireNonNull(resolution, "resolution");
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /**
     * Look up candles for a single trading day. Exclusive with {@code from}/{@code to}/countback.
     */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    /** The leftmost candle (inclusive). */
    public Builder from(LocalDate from) {
      this.from = Objects.requireNonNull(from, "from");
      return this;
    }

    /** The rightmost candle (exclusive). */
    public Builder to(LocalDate to) {
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /** Fetch {@code countback} candles before {@code to}. Positive; pair with {@code to}. */
    public Builder countback(int countback) {
      this.countback = countback;
      return this;
    }

    public FundCandlesRequest build() {
      FundRequests.requireNonEmpty(symbol, "symbol");
      FundRequests.validateWindow(date, from, to, countback);
      return new FundCandlesRequest(this);
    }
  }
}
