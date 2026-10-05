package com.marketdata.sdk.options;

import com.marketdata.sdk.Generated;
import java.util.ArrayList;
import java.util.List;

/**
 * The chain endpoint's {@code ?dte=} parameter accepts four syntactic forms — a single value, a
 * comma-separated list, a closed range, and a comparison — the same syntax the API uses for {@code
 * strike} (see {@link StrikeFilter}). Modeling them as a sealed type with factory entry-points
 * gives the consumer compile-time autocomplete for valid shapes and prevents typos a raw-string
 * passthrough would silently ship to the server.
 *
 * <p>Reached through {@link ExpirationFilter#dte(DteFilter)}, a new overload sitting alongside the
 * pre-existing {@link ExpirationFilter#dte(int)} (single-value only) — the older overload is left
 * unchanged so existing callers keep compiling.
 */
public sealed interface DteFilter
    permits DteFilter.Exact, DteFilter.Values, DteFilter.Range, DteFilter.Comparison {

  /** Days of expiry, whole numbers, per the API's stated bound. */
  int MIN_DAYS = 0;

  int MAX_DAYS = 36500;

  /** Match the expiration closest to {@code days}. */
  static Exact exact(int days) {
    validate(days);
    return new Exact(days);
  }

  /**
   * Match the expiration closest to each value in {@code days}, deduplicated server-side. {@code
   * days} must not be empty.
   */
  static Values values(int... days) {
    List<Integer> boxed = new ArrayList<>(days.length);
    for (int d : days) {
      boxed.add(d);
    }
    return new Values(boxed);
  }

  /** Match every expiration whose dte falls in {@code [min, max]} inclusive. */
  static Range range(int min, int max) {
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    validate(min);
    validate(max);
    return new Range(min, max);
  }

  /** Match every expiration satisfying {@code operator days} (e.g. {@code >= 30}). */
  // @Generated: the null-operator guard is unreachable through the public comparison factories,
  // which always supply a non-null Operator from the typed enum.
  @Generated
  static Comparison comparison(Operator operator, int days) {
    if (operator == null) {
      throw new IllegalArgumentException("operator must not be null");
    }
    validate(days);
    return new Comparison(operator, days);
  }

  private static void validate(int days) {
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException("days must be in " + MIN_DAYS + ".." + MAX_DAYS);
    }
  }

  /** Comparison operators accepted by the API — the same vocabulary as {@link StrikeFilter}. */
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

  record Values(List<Integer> days) implements DteFilter {
    public Values {
      days = List.copyOf(days);
      if (days.isEmpty()) {
        throw new IllegalArgumentException("values must not be empty");
      }
      for (int d : days) {
        validate(d);
      }
    }
  }

  record Range(int min, int max) implements DteFilter {}

  record Comparison(Operator operator, int days) implements DteFilter {}
}
