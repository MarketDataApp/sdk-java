package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/options/chain/{underlying}/}. The chain endpoint exposes the
 * richest filter surface in the API — ~25 query parameters covering expiration selection, strike
 * selection, liquidity floors, side, and structural metadata (weekly / monthly / quarterly /
 * AM-settled / PM-settled / nonstandard).
 *
 * <p>The mutually-exclusive groups are modeled as sealed types ({@link ExpirationFilter}, {@link
 * StrikeFilter}) accessed through a single setter so the compiler enforces "pick one variant". Pair
 * constraints that can't be expressed in the type system ({@code minBid <= maxBid}, {@code minAsk
 * <= maxAsk}) are checked at {@link Builder#build} time — runtime, but pre-HTTP.
 *
 * <p>Filters not validated server-side at construction (the combinatoric of expiration sealed-type
 * variants with weekly / monthly / quarterly booleans is unconstrained) — the SDK trusts the
 * backend to intersect them. Forward-compat: future API parameters drop in as additional builder
 * setters without breaking any existing call.
 */
public final class OptionsChainRequest {

  private final String symbol;

  private final @Nullable ExpirationFilter expirationFilter;
  private final @Nullable Boolean weekly;
  private final @Nullable Boolean monthly;
  private final @Nullable Boolean quarterly;
  private final @Nullable Boolean am;
  private final @Nullable Boolean pm;
  private final @Nullable Boolean nonstandard;

  private final @Nullable StrikeFilter strikeFilter;
  private final @Nullable Double delta;
  private final @Nullable Integer strikeLimit;
  private final @Nullable StrikeRange strikeRange;

  private final @Nullable Double minBid;
  private final @Nullable Double maxBid;
  private final @Nullable Double minAsk;
  private final @Nullable Double maxAsk;
  private final @Nullable Double maxBidAskSpread;
  private final @Nullable Double maxBidAskSpreadPct;
  private final @Nullable Long minOpenInterest;
  private final @Nullable Long minVolume;

  private final @Nullable OptionSide side;

  private final @Nullable LocalDate date;

  private OptionsChainRequest(Builder b) {
    this.symbol = b.symbol;
    this.expirationFilter = b.expirationFilter;
    this.weekly = b.weekly;
    this.monthly = b.monthly;
    this.quarterly = b.quarterly;
    this.am = b.am;
    this.pm = b.pm;
    this.nonstandard = b.nonstandard;
    this.strikeFilter = b.strikeFilter;
    this.delta = b.delta;
    this.strikeLimit = b.strikeLimit;
    this.strikeRange = b.strikeRange;
    this.minBid = b.minBid;
    this.maxBid = b.maxBid;
    this.minAsk = b.minAsk;
    this.maxAsk = b.maxAsk;
    this.maxBidAskSpread = b.maxBidAskSpread;
    this.maxBidAskSpreadPct = b.maxBidAskSpreadPct;
    this.minOpenInterest = b.minOpenInterest;
    this.minVolume = b.minVolume;
    this.side = b.side;
    this.date = b.date;
  }

  /** Shortcut for {@code builder(symbol).build()}. */
  public static OptionsChainRequest of(String symbol) {
    return builder(symbol).build();
  }

  public static Builder builder(String symbol) {
    return new Builder(symbol);
  }

  // ---------- accessors ----------

  public String symbol() {
    return symbol;
  }

  public @Nullable ExpirationFilter expirationFilter() {
    return expirationFilter;
  }

  public @Nullable Boolean weekly() {
    return weekly;
  }

  public @Nullable Boolean monthly() {
    return monthly;
  }

  public @Nullable Boolean quarterly() {
    return quarterly;
  }

  public @Nullable Boolean am() {
    return am;
  }

  public @Nullable Boolean pm() {
    return pm;
  }

  public @Nullable Boolean nonstandard() {
    return nonstandard;
  }

  public @Nullable StrikeFilter strikeFilter() {
    return strikeFilter;
  }

  public @Nullable Double delta() {
    return delta;
  }

  public @Nullable Integer strikeLimit() {
    return strikeLimit;
  }

  public @Nullable StrikeRange strikeRange() {
    return strikeRange;
  }

  public @Nullable Double minBid() {
    return minBid;
  }

  public @Nullable Double maxBid() {
    return maxBid;
  }

  public @Nullable Double minAsk() {
    return minAsk;
  }

  public @Nullable Double maxAsk() {
    return maxAsk;
  }

  public @Nullable Double maxBidAskSpread() {
    return maxBidAskSpread;
  }

  public @Nullable Double maxBidAskSpreadPct() {
    return maxBidAskSpreadPct;
  }

  public @Nullable Long minOpenInterest() {
    return minOpenInterest;
  }

  public @Nullable Long minVolume() {
    return minVolume;
  }

  public @Nullable OptionSide side() {
    return side;
  }

  public @Nullable LocalDate date() {
    return date;
  }

  // ---------- builder ----------

  public static final class Builder {
    private final String symbol;
    private @Nullable ExpirationFilter expirationFilter;
    private @Nullable Boolean weekly;
    private @Nullable Boolean monthly;
    private @Nullable Boolean quarterly;
    private @Nullable Boolean am;
    private @Nullable Boolean pm;
    private @Nullable Boolean nonstandard;
    private @Nullable StrikeFilter strikeFilter;
    private @Nullable Double delta;
    private @Nullable Integer strikeLimit;
    private @Nullable StrikeRange strikeRange;
    private @Nullable Double minBid;
    private @Nullable Double maxBid;
    private @Nullable Double minAsk;
    private @Nullable Double maxAsk;
    private @Nullable Double maxBidAskSpread;
    private @Nullable Double maxBidAskSpreadPct;
    private @Nullable Long minOpenInterest;
    private @Nullable Long minVolume;
    private @Nullable OptionSide side;
    private @Nullable LocalDate date;

    private Builder(String symbol) {
      this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    /** Set the mutually-exclusive expiration-side filter. */
    public Builder expirationFilter(ExpirationFilter filter) {
      this.expirationFilter = Objects.requireNonNull(filter, "filter");
      return this;
    }

    public Builder weekly(boolean value) {
      this.weekly = value;
      return this;
    }

    public Builder monthly(boolean value) {
      this.monthly = value;
      return this;
    }

    public Builder quarterly(boolean value) {
      this.quarterly = value;
      return this;
    }

    public Builder am(boolean value) {
      this.am = value;
      return this;
    }

    public Builder pm(boolean value) {
      this.pm = value;
      return this;
    }

    /** Whether to include non-standard contracts (mini-options, adjusted options, …). */
    public Builder nonstandard(boolean value) {
      this.nonstandard = value;
      return this;
    }

    /** Set the strike-syntax filter ({@code exact}, {@code range}, {@code comparison}). */
    public Builder strikeFilter(StrikeFilter filter) {
      this.strikeFilter = Objects.requireNonNull(filter, "filter");
      return this;
    }

    /** Filter by Black-Scholes delta. Independent of {@link #strikeFilter}. */
    public Builder delta(double value) {
      this.delta = value;
      return this;
    }

    /**
     * Limit the response to {@code n} strikes around the at-the-money line, partitioned by {@link
     * #strikeRange}. Pair semantics: setting one without the other is accepted by the server but
     * may behave unexpectedly — the SDK does not enforce the pair to keep forward-compat.
     */
    public Builder strikeLimit(int n) {
      if (n <= 0) {
        throw new IllegalArgumentException("strikeLimit must be positive");
      }
      this.strikeLimit = n;
      return this;
    }

    public Builder strikeRange(StrikeRange range) {
      this.strikeRange = Objects.requireNonNull(range, "range");
      return this;
    }

    public Builder minBid(double value) {
      this.minBid = value;
      return this;
    }

    public Builder maxBid(double value) {
      this.maxBid = value;
      return this;
    }

    public Builder minAsk(double value) {
      this.minAsk = value;
      return this;
    }

    public Builder maxAsk(double value) {
      this.maxAsk = value;
      return this;
    }

    public Builder maxBidAskSpread(double value) {
      this.maxBidAskSpread = value;
      return this;
    }

    public Builder maxBidAskSpreadPct(double value) {
      this.maxBidAskSpreadPct = value;
      return this;
    }

    public Builder minOpenInterest(long value) {
      if (value < 0) {
        throw new IllegalArgumentException("minOpenInterest must be non-negative");
      }
      this.minOpenInterest = value;
      return this;
    }

    public Builder minVolume(long value) {
      if (value < 0) {
        throw new IllegalArgumentException("minVolume must be non-negative");
      }
      this.minVolume = value;
      return this;
    }

    public Builder side(OptionSide value) {
      this.side = Objects.requireNonNull(value, "side");
      return this;
    }

    /** Historical chain on a specific trading day. */
    public Builder date(LocalDate value) {
      this.date = Objects.requireNonNull(value, "date");
      return this;
    }

    public OptionsChainRequest build() {
      if (symbol.isEmpty()) {
        throw new IllegalArgumentException("symbol must be non-empty");
      }
      if (minBid != null && maxBid != null && minBid > maxBid) {
        throw new IllegalArgumentException("minBid must be <= maxBid");
      }
      if (minAsk != null && maxAsk != null && minAsk > maxAsk) {
        throw new IllegalArgumentException("minAsk must be <= maxAsk");
      }
      return new OptionsChainRequest(this);
    }
  }
}
