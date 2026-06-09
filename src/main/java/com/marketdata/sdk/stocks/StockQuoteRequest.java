package com.marketdata.sdk.stocks;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for the single-symbol form of {@code GET /v1/stocks/quotes/{symbol}/}. For several
 * symbols in one call use {@link StockQuotesRequest} (the backend batches a comma list in a single
 * request).
 *
 * <p>{@code candle} opts the OHLC columns into the response; {@code week52} opts in the 52-week
 * extremes; {@code extended} toggles extended-session prices (default on at the API).
 */
public final class StockQuoteRequest {

  private final String symbol;
  private final @Nullable Boolean extended;
  private final @Nullable Boolean candle;
  private final @Nullable Boolean week52;

  private StockQuoteRequest(Builder b) {
    this.symbol = b.symbol;
    this.extended = b.extended;
    this.candle = b.candle;
    this.week52 = b.week52;
  }

  /** Shortcut for {@code builder(symbol).build()}. */
  public static StockQuoteRequest of(String symbol) {
    return builder(symbol).build();
  }

  public static Builder builder(String symbol) {
    return new Builder(symbol);
  }

  public String symbol() {
    return symbol;
  }

  public @Nullable Boolean extended() {
    return extended;
  }

  public @Nullable Boolean candle() {
    return candle;
  }

  public @Nullable Boolean week52() {
    return week52;
  }

  public static final class Builder {
    private final String symbol;
    private @Nullable Boolean extended;
    private @Nullable Boolean candle;
    private @Nullable Boolean week52;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** Whether to include extended-session prices (API default: true). */
    public Builder extended(boolean extended) {
      this.extended = extended;
      return this;
    }

    /** Add the OHLC columns ({@code open}/{@code high}/{@code low}/{@code close}) to each row. */
    public Builder candle(boolean candle) {
      this.candle = candle;
      return this;
    }

    /** Add the 52-week high/low columns to each row. */
    public Builder week52(boolean week52) {
      this.week52 = week52;
      return this;
    }

    public StockQuoteRequest build() {
      StockRequests.requireNonEmpty(symbol, "symbol");
      return new StockQuoteRequest(this);
    }
  }
}
