package com.marketdata.sdk.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The chain endpoint's {@code ?dte=} parameter accepts three syntactic forms beyond a bare integer
 * — a comma-separated list, a closed range, and a comparison ({@code >=30}, {@code <45}, …) — "the
 * syntax matches strike and delta" per the API's own description, i.e. the same shape {@link
 * StrikeFilter} already models for {@code ?strike=}. Modeling them as a sealed type with factory
 * entry-points gives the consumer compile-time autocomplete for valid shapes and prevents typos a
 * raw-string passthrough would silently ship to the server.
 *
 * <p>A single value is still expressed via {@link ExpirationFilter#dte(int)} (unchanged,
 * non-breaking) — this type covers only the forms a plain {@code int} cannot, reached through
 * {@link ExpirationFilter#dte(DteFilter)}.
 */
public sealed interface DteFilter permits DteFilter.Values, DteFilter.Range, DteFilter.Comparison {

  /** Comma-separated list — selects the closest expiration for each value, deduplicated. */
  static Values values(int first, int... rest) {
    List<Integer> days = new ArrayList<>();
    days.add(first);
    for (int d : rest) {
      days.add(d);
    }
    return new Values(days);
  }

  /** Closed range {@code [min, max]}, inclusive. {@code min} must not exceed {@code max}. */
  static Range range(int min, int max) {
    return new Range(min, max);
  }

  /** Match dte satisfying {@code operator days} (e.g. {@code >= 30}). */
  static Comparison comparison(StrikeFilter.Operator operator, int days) {
    return new Comparison(operator, days);
  }

  /** Whole days, at most 36500, per the API's own description of the parameter. */
  private static void validateDay(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException("dte must be at most 36500");
    }
  }

  /**
   * Comma-separated {@code dte} values — see {@link #values(int, int...)}. {@code days} must be
   * non-empty and carry no {@code null} elements — enforced here, not only in the static factory,
   * since the canonical constructor is itself a public path.
   */
  record Values(List<Integer> days) implements DteFilter {
    public Values {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (Integer d : days) {
        if (d == null) {
          throw new IllegalArgumentException("days must not contain null elements");
        }
        validateDay(d);
      }
      days = List.copyOf(days);
    }
  }

  /** Closed {@code dte} range — see {@link #range(int, int)}. */
  record Range(int min, int max) implements DteFilter {
    public Range {
      validateDay(min);
      validateDay(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /** {@code dte} comparison — see {@link #comparison(StrikeFilter.Operator, int)}. */
  record Comparison(StrikeFilter.Operator operator, int days) implements DteFilter {
    public Comparison {
      Objects.requireNonNull(operator, "operator");
      validateDay(days);
    }
  }
}
