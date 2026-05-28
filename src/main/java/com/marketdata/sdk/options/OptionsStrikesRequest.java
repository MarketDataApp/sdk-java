package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/options/strikes/{symbol}/}. Two optional filters narrow the result:
 * {@code expiration} returns strikes only for that expiration date, {@code date} fetches the
 * historical table as it stood on a previous trading day.
 */
public final class OptionsStrikesRequest {

  private final String symbol;
  private final @Nullable LocalDate expiration;
  private final @Nullable LocalDate date;

  private OptionsStrikesRequest(Builder b) {
    this.symbol = b.symbol;
    this.expiration = b.expiration;
    this.date = b.date;
  }

  /** Shortcut for {@code builder(symbol).build()}. */
  public static OptionsStrikesRequest of(String symbol) {
    return builder(symbol).build();
  }

  public static Builder builder(String symbol) {
    return new Builder(symbol);
  }

  public String symbol() {
    return symbol;
  }

  /** Single-expiration filter, or {@code null} when unset. */
  public @Nullable LocalDate expiration() {
    return expiration;
  }

  /** Historical query date, or {@code null} for the current/last-trading-day table. */
  public @Nullable LocalDate date() {
    return date;
  }

  public static final class Builder {
    private final String symbol;
    private @Nullable LocalDate expiration;
    private @Nullable LocalDate date;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** Restrict the result to the single given expiration date. */
    public Builder expiration(LocalDate expiration) {
      this.expiration = Objects.requireNonNull(expiration, "expiration");
      return this;
    }

    /** Fetch the strikes table as it stood at end-of-day on {@code date}. */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    public OptionsStrikesRequest build() {
      if (symbol.isEmpty()) {
        throw new IllegalArgumentException("symbol must be non-empty");
      }
      return new OptionsStrikesRequest(this);
    }
  }
}
