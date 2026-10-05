package com.marketdata.sdk.options;

import com.marketdata.sdk.Generated;
import java.util.List;
import java.util.Objects;

/**
 * The chain endpoint's {@code ?dte=} parameter accepts three syntactic forms beyond the plain
 * single-value form already modeled by {@link ExpirationFilter#dte(int)} — a comma-separated list,
 * a closed range, and a comparison ({@code >=30}, {@code <45}, …); the syntax matches {@link
 * StrikeFilter}. Modeling them as a sealed type with factory entry-points gives the consumer
 * compile-time autocomplete for valid shapes and prevents typos like {@code "15--30"} that a raw
 * string passthrough would silently ship to the server.
 *
 * <p>Reached through {@link ExpirationFilter#dte(DteFilter)}, which keeps the mutually-exclusive
 * expiration-selection axis (ADR-008) on the single {@code expirationFilter(...)} setter.
 */
public sealed interface DteFilter permits DteFilter.Values, DteFilter.Range, DteFilter.Comparison {

  /** Days are whole numbers of days, at most this many — matches the API's own description. */
  int MAX_DAYS = 36500;

  /**
   * Match the closest expiration for each value in {@code days} (deduplicated server-side). {@code
   * days} must not be empty.
   */
  static Values values(List<Integer> days) {
    Objects.requireNonNull(days, "days");
    if (days.isEmpty()) {
      throw new IllegalArgumentException("days must be non-empty");
    }
    for (int d : days) {
      validateDays(d);
    }
    return new Values(days);
  }

  /** Match every expiration whose dte falls in {@code [min, max]} inclusive. */
  static Range range(int min, int max) {
    validateDays(min);
    validateDays(max);
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    return new Range(min, max);
  }

  /** Match dte satisfying {@code operator days} (e.g. {@code >= 30}). */
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

  /** Comparison operators accepted by the API — mirrors {@link StrikeFilter.Operator}. */
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

  record Values(List<Integer> days) implements DteFilter {
    public Values {
      days = List.copyOf(days);
    }
  }

  record Range(int min, int max) implements DteFilter {}

  record Comparison(Operator operator, int days) implements DteFilter {}
}
