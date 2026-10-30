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
 * <p>{@code dte} itself has four forms on the wire (API 2.0.0 widened it from a bare integer to a
 * string: a single value, a comma-separated list, a closed range, or a comparison operator — "the
 * syntax matches strike and delta" for the latter two). {@link #dte(int)} already covered the
 * single-value form; {@link #dteList}, {@link #dteRange} and {@link #dteComparison} are additive
 * variants covering the other three, reusing {@link StrikeFilter.Operator} for the comparison form
 * since the operator syntax is shared with {@code strike}.
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

  /** Days-to-expiration filter, single value — wire form {@code ?dte=N}. */
  static Dte dte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    return new Dte(days);
  }

  /**
   * Days-to-expiration filter, comma-separated list — wire form {@code ?dte=15,30,45}. Selects the
   * closest expiration for each value (the API deduplicates the result). {@code days} must not be
   * empty, and every value must be in {@code [0, 36500]}.
   */
  static DteList dteList(int... days) {
    if (days.length == 0) {
      throw new IllegalArgumentException("dteList must not be empty");
    }
    List<Integer> boxed = new ArrayList<>(days.length);
    for (int day : days) {
      boxed.add(day);
    }
    return new DteList(boxed);
  }

  /**
   * Days-to-expiration filter, closed range — wire form {@code ?dte=0-45}. Returns every expiration
   * whose {@code dte} falls within {@code [min, max]}, inclusive. {@code min} must not exceed
   * {@code max}, and both must be in {@code [0, 36500]}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration filter, comparison operator — wire form {@code ?dte=>=30} — "the syntax
   * matches strike", so the operator set is {@link StrikeFilter.Operator}. {@code days} must be in
   * {@code [0, 36500]}.
   */
  static DteComparison dteComparison(StrikeFilter.Operator operator, int days) {
    return new DteComparison(operator, days);
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

  /** Days-to-expiration, comma-separated list — see {@link #dteList(int...)}. */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("dteList must not be empty");
      }
      for (Integer day : days) {
        Objects.requireNonNull(day, "days must not contain null values");
        validateDte(day);
      }
      days = List.copyOf(days);
    }
  }

  /** Days-to-expiration, closed range — see {@link #dteRange(int, int)}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      validateDte(min);
      validateDte(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /**
   * Days-to-expiration, comparison operator — see {@link #dteComparison(StrikeFilter.Operator,
   * int)}.
   */
  record DteComparison(StrikeFilter.Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      validateDte(days);
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}

  /**
   * Shared bound for every {@code dte} value across the new forms (API 2.0.0: "Values are whole
   * numbers of days, at most 36500"). Not applied to {@link #dte(int)} — that form predates the
   * bound and keeps the error it has always failed with.
   */
  private static void validateDte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException("dte must be at most 36500");
    }
  }
}
