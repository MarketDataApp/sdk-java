package com.marketdata.sdk.stocks;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/stocks/news/{symbol}/}. The ticker is required; the optional window
 * follows the shared rules ({@code date} is single-day and exclusive with {@code from}/{@code
 * to}/{@code countback}; {@code countback} pairs with {@code to}).
 */
public final class StockNewsRequest {

  private final String symbol;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;

  private StockNewsRequest(Builder b) {
    this.symbol = b.symbol;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
  }

  /** Shortcut for {@code builder(symbol).build()}. */
  public static StockNewsRequest of(String symbol) {
    return builder(symbol).build();
  }

  public static Builder builder(String symbol) {
    return new Builder(symbol);
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
    private final String symbol;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** Retrieve news for a single day. Exclusive with {@code from}/{@code to}/{@code countback}. */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    /** The earliest article to include. */
    public Builder from(LocalDate from) {
      this.from = Objects.requireNonNull(from, "from");
      return this;
    }

    /** The latest article to include. */
    public Builder to(LocalDate to) {
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /** Fetch {@code countback} articles before {@code to}. Positive; pair with {@code to}. */
    public Builder countback(int countback) {
      this.countback = countback;
      return this;
    }

    public StockNewsRequest build() {
      StockRequests.requireNonEmpty(symbol, "symbol");
      StockRequests.validateWindow(date, from, to, countback);
      return new StockNewsRequest(this);
    }
  }
}
