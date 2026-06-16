package com.marketdata.sdk.stocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameters for {@code GET /v1/stocks/prices/?symbols=A,B,C} — one or more ticker symbols, batched
 * into a single request. The backend returns one {@link StockPrice} row per symbol.
 */
public final class StockPricesRequest {

  private final List<String> symbols;

  private StockPricesRequest(Builder b) {
    this.symbols = List.copyOf(b.symbols);
  }

  /** Shortcut for {@code builder(first, rest...).build()}. */
  public static StockPricesRequest of(String first, String... rest) {
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

  public static final class Builder {
    private final List<String> symbols = new ArrayList<>();

    private Builder() {}

    public Builder addSymbol(String symbol) {
      Objects.requireNonNull(symbol, "symbol");
      StockRequests.requireNonEmpty(symbol, "symbol");
      this.symbols.add(symbol);
      return this;
    }

    public StockPricesRequest build() {
      if (symbols.isEmpty()) {
        throw new IllegalArgumentException("at least one symbol is required");
      }
      return new StockPricesRequest(this);
    }
  }
}
