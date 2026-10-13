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
 * <p>{@code dte} itself has four wire syntaxes — single value ({@link #dte(int)}), comma-separated
 * list ({@link #dteList(int...)}), closed range ({@link #dteRange(int, int)}), and comparison
 * ({@link #dteComparison(StrikeFilter.Operator, int)}) — modeled as four sibling variants rather
 * than widening {@link #dte(int)} itself, so the pre-existing single-value call keeps compiling and
 * keeps whatever error (local or server-side) it produced before.
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

  /**
   * Upper bound on a single {@code dte} value, shared by every {@code dte} syntax — per the API.
   */
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

  /**
   * Single days-to-expiration filter — wire form {@code ?dte=N}. Limits the chain to the one
   * expiration whose dte is closest to {@code days}.
   */
  static Dte dte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    return new Dte(days);
  }

  /**
   * Days-to-expiration list filter — wire form {@code ?dte=15,30,45}. The API resolves the closest
   * expiration to each value independently, deduplicated. {@code days} must be non-empty; every
   * value is validated the same way as {@link #dte(int)}.
   */
  static DteList dteList(int... days) {
    List<Integer> list = new ArrayList<>(days.length);
    for (int day : days) {
      list.add(day);
    }
    return new DteList(list);
  }

  /**
   * Days-to-expiration range filter — wire form {@code ?dte=0-45}. Returns every expiration whose
   * dte falls in {@code [min, max]} inclusive. {@code min} must not exceed {@code max}; both bounds
   * are validated the same way as {@link #dte(int)}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison filter — wire form {@code ?dte=>=30} / {@code ?dte=<45}. Returns
   * every expiration whose dte satisfies {@code operator days}. {@code days} is validated the same
   * way as {@link #dte(int)}.
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

  // Dte predates MAX_DTE_DAYS and keeps its original, factory-only non-negative check (no compact
  // constructor) so an existing caller's behavior — including constructing it unvalidated via
  // `new Dte(...)` — does not change for this release.
  record Dte(int days) implements ExpirationFilter {}

  /** See {@link #dteList(int...)}. {@code days} is defensively copied and immutable. */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("dteList must not be empty");
      }
      for (Integer day : days) {
        Objects.requireNonNull(day, "days element");
        validateDteValue(day);
      }
      days = List.copyOf(days);
    }
  }

  /** See {@link #dteRange(int, int)}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      validateDteValue(min);
      validateDteValue(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /** See {@link #dteComparison(StrikeFilter.Operator, int)}. */
  record DteComparison(StrikeFilter.Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      validateDteValue(days);
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}

  /** Bounds check backing {@link #dteList}, {@link #dteRange}, and {@link #dteComparison}. */
  private static void validateDteValue(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > MAX_DTE_DAYS) {
      throw new IllegalArgumentException("dte must be at most " + MAX_DTE_DAYS);
    }
  }
}
