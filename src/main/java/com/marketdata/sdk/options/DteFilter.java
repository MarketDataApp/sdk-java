package com.marketdata.sdk.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The chain endpoint's {@code ?dte=} parameter accepts four syntactic forms: a single value
 * (handled directly by {@link ExpirationFilter#dte(int)}), a comma-separated list, a closed range,
 * and a comparison ({@code >=30}, {@code <45}, …). This sealed type covers the three multi-value
 * forms — reach them through {@link ExpirationFilter#dte(DteFilter)}.
 *
 * <p>Modeling them as a sealed type (mirroring {@link StrikeFilter}) gives the consumer
 * compile-time autocomplete for valid shapes and prevents a hand-assembled range or comparison
 * string from shipping a typo to the server. Every value, in every form, is a whole number of days
 * in {@code [0, 36500]} — the bound the API documents.
 */
public sealed interface DteFilter
    permits DteFilter.ValueList, DteFilter.Range, DteFilter.Comparison {

  /** The largest {@code dte} value the API accepts. */
  int MAX_DAYS = 36500;

  /**
   * Comma-separated list of values — wire form {@code ?dte=15,30,45}. Selects the expiration
   * closest to each value (deduplicated server-side). At least one value is required.
   */
  static ValueList list(int first, int... rest) {
    List<Integer> days = new ArrayList<>();
    days.add(first);
    for (int d : rest) {
      days.add(d);
    }
    return new ValueList(days);
  }

  /**
   * Closed, inclusive range — wire form {@code ?dte=0-45}. {@code min} must not exceed {@code max}.
   */
  static Range range(int min, int max) {
    return new Range(min, max);
  }

  /** Match {@code dte} values satisfying {@code operator days} (e.g. {@code >= 30}). */
  static Comparison comparison(Operator operator, int days) {
    return new Comparison(operator, days);
  }

  private static int validate(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > MAX_DAYS) {
      throw new IllegalArgumentException("dte must be at most " + MAX_DAYS);
    }
    return days;
  }

  /** Comparison operators accepted by the API — same syntax as {@link StrikeFilter.Operator}. */
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

  /**
   * @param days at least one value, each in {@code [0, 36500]}. Immutable; never {@code null},
   *     empty, or containing a {@code null} element.
   */
  record ValueList(List<Integer> days) implements DteFilter {
    public ValueList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      List<Integer> validated = new ArrayList<>(days.size());
      for (Integer d : days) {
        validated.add(validate(Objects.requireNonNull(d, "days element")));
      }
      days = List.copyOf(validated);
    }
  }

  /**
   * @param min lower bound of the closed range, inclusive, in {@code [0, 36500]}. Must not exceed
   *     {@code max}.
   * @param max upper bound of the closed range, inclusive, in {@code [0, 36500]}. Must not be less
   *     than {@code min}.
   */
  record Range(int min, int max) implements DteFilter {
    public Range {
      validate(min);
      validate(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /**
   * @param operator the comparison to apply against {@code days}; never {@code null}.
   * @param days the threshold, in {@code [0, 36500]}.
   */
  record Comparison(Operator operator, int days) implements DteFilter {
    public Comparison {
      Objects.requireNonNull(operator, "operator");
      validate(days);
    }
  }
}
