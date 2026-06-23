package com.marketdata.sdk.options;

import com.marketdata.sdk.Generated;

/**
 * The chain endpoint's {@code ?strike=} parameter accepts three syntactic forms — exact value,
 * range, and comparison ({@code >150}, {@code <=160}, …). Modeling them as a sealed type with
 * factory entry-points gives the consumer compile-time autocomplete for valid shapes and prevents
 * typos like {@code "140--160"} that a raw-string passthrough would silently ship to the server.
 *
 * <p>Other strike-related chain parameters ({@code delta}, {@code strikeLimit} + {@code range},
 * {@code minBid}/{@code maxBid}, …) are independent filters server-side — they intersect with this
 * one rather than overriding it, so they stay as separate setters on the request builder.
 */
public sealed interface StrikeFilter
    permits StrikeFilter.Exact, StrikeFilter.Range, StrikeFilter.Comparison {

  /** Match options whose strike equals {@code price}. */
  static Exact exact(double price) {
    return new Exact(price);
  }

  /** Match strikes in {@code [min, max]} inclusive. {@code min} must not exceed {@code max}. */
  static Range range(double min, double max) {
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    return new Range(min, max);
  }

  /** Match strikes satisfying {@code operator price} (e.g. {@code > 150}). */
  // @Generated: the null-operator guard is unreachable through the public comparison factories,
  // which always supply a non-null Operator from the typed enum.
  @Generated
  static Comparison comparison(Operator operator, double price) {
    if (operator == null) {
      throw new IllegalArgumentException("operator must not be null");
    }
    return new Comparison(operator, price);
  }

  /** Comparison operators accepted by the API. */
  enum Operator {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<=");

    private final String wireValue;

    Operator(String wireValue) {
      this.wireValue = wireValue;
    }

    /** The wire-form prefix the API expects, e.g. {@code ">"}. */
    public String wireValue() {
      return wireValue;
    }
  }

  record Exact(double price) implements StrikeFilter {}

  record Range(double min, double max) implements StrikeFilter {}

  record Comparison(Operator operator, double price) implements StrikeFilter {}
}
