package com.marketdata.sdk.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Extended days-to-expiration filter for {@code /v1/options/chain/} — the forms of {@code ?dte=}
 * beyond a single value ({@link ExpirationFilter#dte(int)}): a comma-separated list of values, a
 * closed range, and a comparison. The range and comparison syntax match {@link StrikeFilter}'s
 * ({@code 0-45}, {@code >=30}) — {@link #comparison} reuses {@link StrikeFilter.Operator}.
 *
 * <p>A permitted subtype of {@link ExpirationFilter}, not a replacement for {@link
 * ExpirationFilter#dte(int)}: the chain endpoint's expiration-selection axis stays a single pick,
 * enforced by the one {@code expirationFilter(...)} setter on the request builder.
 */
public sealed interface DteFilter extends ExpirationFilter
    permits DteFilter.Values, DteFilter.Range, DteFilter.Comparison {

  /**
   * A comma-separated list of days-to-expiration values — wire form {@code ?dte=15,30,45}. Each
   * value selects its own closest expiration; the server deduplicates the result. Each value must
   * be non-negative and at most 36500.
   */
  static Values values(int... days) {
    Objects.requireNonNull(days, "days");
    List<Integer> boxed = new ArrayList<>(days.length);
    for (int day : days) {
      boxed.add(day);
    }
    return new Values(boxed);
  }

  /**
   * Closed days-to-expiration range, inclusive on both ends — wire form {@code ?dte=min-max}.
   * {@code min} must not exceed {@code max}, and both must be non-negative and at most 36500.
   */
  static Range range(int min, int max) {
    return new Range(min, max);
  }

  /**
   * Days-to-expiration comparison — wire form e.g. {@code ?dte=>=30}. {@code days} must be
   * non-negative and at most 36500.
   */
  static Comparison comparison(StrikeFilter.Operator operator, int days) {
    return new Comparison(operator, days);
  }

  /** Days to expiry are whole numbers, non-negative, and at most 36500. */
  private static void validateDays(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException("dte must be at most 36500");
    }
  }

  record Values(List<Integer> days) implements DteFilter {
    public Values {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (Integer day : days) {
        if (day == null) {
          throw new IllegalArgumentException("days must not contain null elements");
        }
        validateDays(day);
      }
      days = List.copyOf(days);
    }
  }

  record Range(int min, int max) implements DteFilter {
    public Range {
      validateDays(min);
      validateDays(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  record Comparison(StrikeFilter.Operator operator, int days) implements DteFilter {
    public Comparison {
      Objects.requireNonNull(operator, "operator");
      validateDays(days);
    }
  }
}
