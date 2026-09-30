package com.marketdata.sdk.options;

import com.marketdata.sdk.Generated;
import java.util.ArrayList;
import java.util.List;

/**
 * The chain endpoint's {@code ?dte=} parameter accepts four syntactic forms — a single value, a
 * comma-separated list, a closed range, and a comparison ({@code >=30}, {@code <45}, …). The API
 * docs say the range/comparison syntax "matches strike and delta", so this mirrors {@link
 * StrikeFilter}'s treatment of {@code ?strike=} for those two forms; the comma-separated list is
 * unique to {@code dte} (it selects the closest expiration for each value, deduplicated
 * server-side).
 *
 * <p>Every value across every variant must be a whole number of days in {@code [0, 36500]}.
 */
public sealed interface DteFilter
    permits DteFilter.Exact, DteFilter.ListOf, DteFilter.Range, DteFilter.Comparison {

  /** Maximum value the API accepts for a dte day-count. */
  int MAX_DAYS = 36500;

  /** Match the expiration whose dte is closest to {@code days}. */
  static Exact exact(int days) {
    validateDays(days);
    return new Exact(days);
  }

  /** Match the closest expiration for each value in {@code days} — deduplicated server-side. */
  static ListOf list(int... days) {
    if (days.length == 0) {
      throw new IllegalArgumentException("days must be non-empty");
    }
    List<Integer> boxed = new ArrayList<>(days.length);
    for (int d : days) {
      validateDays(d);
      boxed.add(d);
    }
    return new ListOf(List.copyOf(boxed));
  }

  /** Match every expiration whose dte falls in {@code [min, max]} inclusive. */
  static Range range(int min, int max) {
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    validateDays(min);
    validateDays(max);
    return new Range(min, max);
  }

  /** Match every expiration whose dte satisfies {@code operator days} (e.g. {@code >= 30}). */
  // @Generated: the null-operator guard is unreachable through the public comparison factories,
  // which always supply a non-null Operator from the typed enum.
  @Generated
  static Comparison comparison(Operator operator, int days) {
    if (operator == null) {
      throw new IllegalArgumentException("operator must not be null");
    }
    validateDays(days);
    return new Comparison(operator, days);
  }

  private static void validateDays(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > MAX_DAYS) {
      throw new IllegalArgumentException("dte must be at most " + MAX_DAYS);
    }
  }

  /** Comparison operators accepted by the API — the same four as {@link StrikeFilter.Operator}. */
  enum Operator {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<=");

    private final String wireValue;

    Operator(String wireValue) {
      this.wireValue = wireValue;
    }

    /** The wire-form prefix the API expects, e.g. {@code ">="}. */
    public String wireValue() {
      return wireValue;
    }
  }

  record Exact(int days) implements DteFilter {}

  record ListOf(List<Integer> days) implements DteFilter {}

  record Range(int min, int max) implements DteFilter {}

  record Comparison(Operator operator, int days) implements DteFilter {}
}
