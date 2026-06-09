package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/options/expirations/{symbol}/} — the list of expiration dates for
 * an underlying's option chain. Two optional filters narrow the result: {@code strike} restricts to
 * expirations that include the given strike, and {@code date} fetches the historical list as it
 * stood on a previous trading day.
 *
 * <p>Constructed via {@link #builder(String)}; the {@code symbol} is required and seeds the
 * builder, mutable optional fields are set with {@code strike(...)} / {@code date(...)}. The §3
 * universal {@code dateformat}/{@code format}/etc. parameters are not part of this class — they
 * land on a separate universal-parameters overload when added.
 */
public final class OptionsExpirationsRequest {

  private final String symbol;
  private final @Nullable Double strike;
  private final @Nullable LocalDate date;

  private OptionsExpirationsRequest(Builder b) {
    this.symbol = b.symbol;
    this.strike = b.strike;
    this.date = b.date;
  }

  /** Shortcut for {@code builder(symbol).build()} when no filters are needed. */
  public static OptionsExpirationsRequest of(String symbol) {
    return builder(symbol).build();
  }

  /** Start a builder seeded with the required underlying symbol. */
  public static Builder builder(String symbol) {
    return new Builder(symbol);
  }

  public String symbol() {
    return symbol;
  }

  /** Strike-price filter, or {@code null} when unset. */
  public @Nullable Double strike() {
    return strike;
  }

  /** Historical query date, or {@code null} for the current/last-trading-day list. */
  public @Nullable LocalDate date() {
    return date;
  }

  public static final class Builder {
    private final String symbol;
    private @Nullable Double strike;
    private @Nullable LocalDate date;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** Restrict the result to expirations whose chain contains {@code strike}. */
    public Builder strike(double strike) {
      this.strike = strike;
      return this;
    }

    /** Fetch the expirations list as it stood at end-of-day on {@code date}. */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    public OptionsExpirationsRequest build() {
      if (symbol.isEmpty()) {
        throw new IllegalArgumentException("symbol must be non-empty");
      }
      return new OptionsExpirationsRequest(this);
    }
  }
}
