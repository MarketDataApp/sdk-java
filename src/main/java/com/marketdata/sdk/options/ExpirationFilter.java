package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutually-exclusive expiration filter for {@code /v1/options/chain/}. The chain endpoint's
 * expiration-side parameters ({@code expiration}, {@code dte}, {@code from}/{@code to}, {@code
 * month}/{@code year}) cover overlapping selection axes; combining them produces undefined behavior
 * server-side. Modeling them as variants of a sealed interface with a single {@code
 * expirationFilter(...)} setter on the request builder makes that exclusivity compiler-enforced:
 * there is no way to assign two variants at once.
 *
 * <p>Additive expiration-type predicates ({@code weekly}/{@code monthly}/{@code quarterly}/{@code
 * am}/{@code pm}) are not part of this hierarchy — they intersect freely with any variant and stay
 * as separate booleans on the request builder.
 *
 * <p>The {@code dte} axis itself accepts four wire syntaxes — a single value ({@link #dte(int)}), a
 * comma-separated list ({@link #dteList}), a closed range ({@link #dteRange}), and a comparison
 * operator ({@link #dteComparison}), mirroring {@link StrikeFilter}'s syntax. {@code dte(int)}
 * keeps its original signature and {@link Dte} record shape unchanged — the new syntaxes are
 * additive variants rather than a retyping, so existing callers and compiled callers are
 * unaffected.
 */
public sealed interface ExpirationFilter
    permits ExpirationFilter.OnDate,
        ExpirationFilter.Dte,
        ExpirationFilter.DteList,
        ExpirationFilter.DteRange,
        ExpirationFilter.DteComparison,
        ExpirationFilter.Between,
        ExpirationFilter.MonthYear,
        ExpirationFilter.All {

  /** The API's upper bound on any individual {@code dte} value, in days (~100 years). */
  int MAX_DTE_DAYS = 36500;

  /** A specific expiration date — wire form {@code ?expiration=YYYY-MM-DD}. */
  static OnDate onDate(LocalDate date) {
    return new OnDate(date);
  }

  /**
   * Every available expiration — wire form {@code ?expiration=all}.
   *
   * <p>This is <em>not</em> equivalent to leaving the expiration filter unset: with no filter the
   * chain endpoint returns only the front-month (nearest) expiration, whereas {@code all()} returns
   * the full chain across every expiration. The additive {@code weekly}/{@code monthly}/{@code
   * quarterly} predicates still narrow the result on top of it.
   */
  static All all() {
    return new All();
  }

  /** Days-to-expiration filter — wire form {@code ?dte=N}. */
  static Dte dte(int days) {
    validateDteDays(days);
    return new Dte(days);
  }

  /**
   * Days-to-expiration comma-separated list — wire form {@code ?dte=15,30,45}. Selects the closest
   * expiration for each value, deduplicated by the API. Mirrors {@link StrikeFilter}'s syntax.
   */
  static DteList dteList(int... days) {
    Objects.requireNonNull(days, "days");
    List<Integer> copy = new ArrayList<>(days.length);
    for (int day : days) {
      copy.add(day);
    }
    return new DteList(List.copyOf(copy));
  }

  /**
   * Days-to-expiration closed range — wire form {@code ?dte=min-max}, inclusive. {@code min} must
   * not exceed {@code max}. Mirrors {@link StrikeFilter}'s syntax.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison — wire form {@code ?dte=operator+days} (e.g. {@code >=30}).
   * Mirrors {@link StrikeFilter}'s syntax.
   */
  static DteComparison dteComparison(DteOperator operator, int days) {
    return new DteComparison(operator, days);
  }

  private static void validateDteDays(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > MAX_DTE_DAYS) {
      throw new IllegalArgumentException("dte must be at most " + MAX_DTE_DAYS);
    }
  }

  /**
   * Inclusive date range — wire form {@code ?from=YYYY-MM-DD&to=YYYY-MM-DD}. {@code from} must not
   * be strictly after {@code to}.
   */
  static Between between(LocalDate from, LocalDate to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from must be on or before to");
    }
    return new Between(from, to);
  }

  /**
   * Calendar month-of-year filter — wire form {@code ?month=M&year=YYYY}. {@code month} is the
   * 1-based calendar month (January = 1).
   */
  static MonthYear monthYear(int year, int month) {
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("month must be in 1..12");
    }
    return new MonthYear(year, month);
  }

  record OnDate(LocalDate date) implements ExpirationFilter {
    public OnDate {
      Objects.requireNonNull(date, "date");
    }
  }

  record Dte(int days) implements ExpirationFilter {}

  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      days = List.copyOf(days);
      for (int day : days) {
        validateDteDays(day);
      }
    }
  }

  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      validateDteDays(min);
      validateDteDays(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  record DteComparison(DteOperator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      validateDteDays(days);
    }
  }

  /**
   * Comparison operators accepted by the {@code dte} parameter. Mirrors {@link
   * StrikeFilter.Operator}.
   */
  enum DteOperator {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<=");

    private final String wireValue;

    DteOperator(String wireValue) {
      this.wireValue = wireValue;
    }

    /** The wire-form prefix the API expects, e.g. {@code ">="}. */
    public String wireValue() {
      return wireValue;
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}
}
