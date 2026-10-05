package com.marketdata.sdk.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Richer forms of the chain endpoint's {@code ?dte=} parameter, beyond the single value already
 * covered by {@link ExpirationFilter#dte(int)}: a comma-separated list, a closed range, and a
 * comparison operator — the same three syntactic shapes {@link StrikeFilter} models for {@code
 * ?strike=}. {@code DteFilter} extends {@link ExpirationFilter} directly (rather than wrapping it)
 * so every variant can be passed straight to {@code expirationFilter(...)}, preserving the "pick
 * one variant" compile-time exclusivity ADR-008 established.
 *
 * <p>{@link ExpirationFilter#dte(DteFilter)} is a pass-through overload kept for discoverability
 * under the existing {@code dte(...)} name.
 */
public sealed interface DteFilter extends ExpirationFilter
    permits DteFilter.Values, DteFilter.Range, DteFilter.Comparison {

  /** Upper bound the API documents for a single {@code dte} value, inclusive. */
  int MAX_DAYS = 36500;

  /**
   * Comma-separated list — wire form {@code ?dte=15,30,45}. The API selects the closest expiration
   * for each value, deduplicated.
   */
  static Values values(int first, int... rest) {
    List<Integer> days = new ArrayList<>();
    days.add(first);
    for (int day : rest) {
      days.add(day);
    }
    for (int day : days) {
      requireValidDays(day);
    }
    return new Values(List.copyOf(days));
  }

  /** Closed range — wire form {@code ?dte=min-max}, inclusive of both bounds. */
  static Range range(int min, int max) {
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    requireValidDays(min);
    requireValidDays(max);
    return new Range(min, max);
  }

  /** Comparison operator — wire form e.g. {@code ?dte=>=30}. */
  static Comparison comparison(Operator operator, int days) {
    Objects.requireNonNull(operator, "operator");
    requireValidDays(days);
    return new Comparison(operator, days);
  }

  private static void requireValidDays(int days) {
    if (days < 0 || days > MAX_DAYS) {
      throw new IllegalArgumentException("dte must be in 0.." + MAX_DAYS);
    }
  }

  /** Comparison operators accepted by the API — same four as {@link StrikeFilter.Operator}. */
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

  record Values(List<Integer> days) implements DteFilter {}

  record Range(int min, int max) implements DteFilter {}

  record Comparison(Operator operator, int days) implements DteFilter {}
}
