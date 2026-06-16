package com.marketdata.sdk.stocks;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/stocks/earnings/{symbol}/}. The ticker is required; the optional
 * window follows the shared rules. {@code report} selects a specific fiscal report by {@code
 * YYYY-Qn} (e.g. {@code "2023-Q4"}) without needing the company's fiscal calendar.
 */
public final class StockEarningsRequest {

  private final String symbol;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;
  private final @Nullable String report;

  private StockEarningsRequest(Builder b) {
    this.symbol = b.symbol;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
    this.report = b.report;
  }

  /** Shortcut for {@code builder(symbol).build()}. */
  public static StockEarningsRequest of(String symbol) {
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

  public @Nullable String report() {
    return report;
  }

  public static final class Builder {
    private final String symbol;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;
    private @Nullable String report;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /**
     * Retrieve a single report by date. Exclusive with {@code from}/{@code to}/{@code countback}.
     */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    /** The earliest report to include. */
    public Builder from(LocalDate from) {
      this.from = Objects.requireNonNull(from, "from");
      return this;
    }

    /** The latest report to include. */
    public Builder to(LocalDate to) {
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /** Fetch {@code countback} reports before {@code to}. Positive; pair with {@code to}. */
    public Builder countback(int countback) {
      this.countback = countback;
      return this;
    }

    /** Retrieve a specific fiscal report, e.g. {@code "2023-Q4"}. */
    public Builder report(String report) {
      this.report = Objects.requireNonNull(report, "report");
      return this;
    }

    public StockEarningsRequest build() {
      StockRequests.requireNonEmpty(symbol, "symbol");
      StockRequests.validateWindow(date, from, to, countback);
      return new StockEarningsRequest(this);
    }
  }
}
