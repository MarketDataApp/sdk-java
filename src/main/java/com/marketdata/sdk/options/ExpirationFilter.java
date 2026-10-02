package com.marketdata.sdk.options;

import com.marketdata.sdk.Generated;
import java.time.LocalDate;
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
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    return new Dte(days);
  }

  /**
   * Days-to-expiration list — wire form {@code ?dte=15,30,45}. Selects the closest expiration for
   * each value (deduplicated server-side).
   */
  static DteList dteList(List<Integer> days) {
    Objects.requireNonNull(days, "days");
    if (days.isEmpty()) {
      throw new IllegalArgumentException("days must not be empty");
    }
    for (int d : days) {
      validateDte(d);
    }
    return new DteList(List.copyOf(days));
  }

  /**
   * Days-to-expiration range — wire form {@code ?dte=0-45}. Matches every expiration whose dte
   * falls within the bounds, inclusive. {@code min} must not exceed {@code max}.
   */
  static DteRange dteRange(int min, int max) {
    validateDte(min);
    validateDte(max);
    if (min > max) {
      throw new IllegalArgumentException("min must be <= max");
    }
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison — wire form {@code ?dte=>=30}. Matches every expiration whose dte
   * satisfies {@code operator days}.
   */
  // @Generated: the null-operator guard is unreachable through the public comparison factory,
  // which always supplies a non-null Operator from the typed enum.
  @Generated
  static DteComparison dteComparison(Operator operator, int days) {
    if (operator == null) {
      throw new IllegalArgumentException("operator must not be null");
    }
    validateDte(days);
    return new DteComparison(operator, days);
  }

  private static void validateDte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
  }

  /** Comparison operators accepted by the {@code dte} filter. */
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

  record DteList(List<Integer> days) implements ExpirationFilter {}

  record DteRange(int min, int max) implements ExpirationFilter {}

  record DteComparison(Operator operator, int days) implements ExpirationFilter {}

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}
}
