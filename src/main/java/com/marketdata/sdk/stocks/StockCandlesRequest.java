package com.marketdata.sdk.stocks;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/stocks/candles/{resolution}/{symbol}/}. The {@link StockResolution}
 * and the ticker {@code symbol} are required; the rest bound the window and tune
 * adjustment/exchange resolution.
 *
 * <p>Window rules (enforced in {@link Builder#build()}): {@code date} selects a single trading day
 * and is incompatible with {@code from}/{@code to}/{@code countback}; {@code countback} pairs with
 * {@code to} (not {@code from}) and must be positive.
 */
public final class StockCandlesRequest {

  private final StockResolution resolution;
  private final String symbol;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;
  private final @Nullable String exchange;
  private final @Nullable Boolean extended;
  private final @Nullable String country;
  private final @Nullable Boolean adjustSplits;
  private final @Nullable Boolean adjustDividends;

  private StockCandlesRequest(Builder b) {
    this.resolution = b.resolution;
    this.symbol = b.symbol;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
    this.exchange = b.exchange;
    this.extended = b.extended;
    this.country = b.country;
    this.adjustSplits = b.adjustSplits;
    this.adjustDividends = b.adjustDividends;
  }

  /** Shortcut for {@code builder(resolution, symbol).build()}. */
  public static StockCandlesRequest of(StockResolution resolution, String symbol) {
    return builder(resolution, symbol).build();
  }

  public static Builder builder(StockResolution resolution, String symbol) {
    return new Builder(resolution, symbol);
  }

  public StockResolution resolution() {
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

  public @Nullable String exchange() {
    return exchange;
  }

  public @Nullable Boolean extended() {
    return extended;
  }

  public @Nullable String country() {
    return country;
  }

  public @Nullable Boolean adjustSplits() {
    return adjustSplits;
  }

  public @Nullable Boolean adjustDividends() {
    return adjustDividends;
  }

  public static final class Builder {
    private final StockResolution resolution;
    private final String symbol;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;
    private @Nullable String exchange;
    private @Nullable Boolean extended;
    private @Nullable String country;
    private @Nullable Boolean adjustSplits;
    private @Nullable Boolean adjustDividends;

    private Builder(StockResolution resolution, String symbol) {
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

    /** Disambiguate the exchange (acronym, MIC code, or two-digit Yahoo code). */
    public Builder exchange(String exchange) {
      this.exchange = Objects.requireNonNull(exchange, "exchange");
      return this;
    }

    /** Include extended-hours sessions on intraday candles (daily never returns extended). */
    public Builder extended(boolean extended) {
      this.extended = extended;
      return this;
    }

    /** Disambiguate the exchange country (two-digit ISO 3166 code). */
    public Builder country(String country) {
      this.country = Objects.requireNonNull(country, "country");
      return this;
    }

    /** Adjust for splits (daily default true, intraday default false). */
    public Builder adjustSplits(boolean adjustSplits) {
      this.adjustSplits = adjustSplits;
      return this;
    }

    /** Adjust for dividends (daily default true, intraday default false). */
    public Builder adjustDividends(boolean adjustDividends) {
      this.adjustDividends = adjustDividends;
      return this;
    }

    public StockCandlesRequest build() {
      StockRequests.requireNonEmpty(symbol, "symbol");
      StockRequests.validateWindow(date, from, to, countback);
      return new StockCandlesRequest(this);
    }
  }
}
