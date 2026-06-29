package com.marketdata.sdk.stocks;

import com.marketdata.sdk.Generated;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for the multi-symbol form of {@code GET /v1/stocks/quotes/?symbols=A,B,C}. Unlike the
 * options multi-quote (which fans out one request per contract), the stocks backend accepts a comma
 * list in a <em>single</em> request and returns one row per symbol — so this maps to one {@code
 * StockQuotesResponse}, not a per-symbol map.
 *
 * <p>For a single symbol prefer {@link StockQuoteRequest}.
 */
public final class StockQuotesRequest {

  private final List<String> symbols;
  private final @Nullable Boolean extended;
  private final @Nullable Boolean candle;
  private final @Nullable Boolean week52;

  private StockQuotesRequest(Builder b) {
    this.symbols = List.copyOf(b.symbols);
    this.extended = b.extended;
    this.candle = b.candle;
    this.week52 = b.week52;
  }

  /** Shortcut for {@code builder(first, rest...).build()}. */
  public static StockQuotesRequest of(String first, String... rest) {
    return builder(first, rest).build();
  }

  /** Start a builder with one or more ticker symbols. At least one is required. */
  public static Builder builder(String first, String... rest) {
    Builder b = new Builder();
    b.addSymbol(first);
    for (String s : rest) {
      b.addSymbol(s);
    }
    return b;
  }

  public List<String> symbols() {
    return symbols;
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
    private final List<String> symbols = new ArrayList<>();
    private @Nullable Boolean extended;
    private @Nullable Boolean candle;
    private @Nullable Boolean week52;

    private Builder() {}

    public Builder addSymbol(String symbol) {
      Objects.requireNonNull(symbol, "symbol");
      StockRequests.requireNonEmpty(symbol, "symbol");
      this.symbols.add(symbol);
      return this;
    }

    /** Whether to include extended-session prices (API default: true). */
    public Builder extended(boolean extended) {
      this.extended = extended;
      return this;
    }

    /** Add the OHLC columns to each row. */
    public Builder candle(boolean candle) {
      this.candle = candle;
      return this;
    }

    /** Add the 52-week high/low columns to each row. */
    public Builder week52(boolean week52) {
      this.week52 = week52;
      return this;
    }

    // @Generated: the empty-symbols guard is unreachable — builder(first, ...) always seeds one
    // symbol and the constructor is private, so the list is never empty here.
    @Generated
    public StockQuotesRequest build() {
      if (symbols.isEmpty()) {
        throw new IllegalArgumentException("at least one symbol is required");
      }
      return new StockQuotesRequest(this);
    }
  }
}
