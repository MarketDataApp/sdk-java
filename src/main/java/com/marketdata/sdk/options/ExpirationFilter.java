package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.Arrays;
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
 * <p>{@code dte} itself has four wire forms (the API's {@code dte} parameter is string-typed as of
 * v2.0.0): a single value ({@link #dte(int)}), a comma-separated list ({@link #dte(int...)}), a
 * closed range ({@link #dteRange}), and a comparison operator ({@link #dteComparison}) — the last
 * two share their operator/range syntax with {@link StrikeFilter}.
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
   * Days-to-expiration list filter — wire form {@code ?dte=15,30,45}. Selects the expiration
   * closest to each value, deduplicated server-side. {@code days} must be non-empty; every value
   * must be in {@code [0, 36500]}.
   */
  static DteList dte(int... days) {
    return new DteList(Arrays.stream(days).boxed().toList());
  }

  /**
   * Days-to-expiration closed range filter — wire form {@code ?dte=min-max}. Matches every
   * expiration whose dte falls within {@code [min, max]}, inclusive. {@code min} must not exceed
   * {@code max}; both bounds must be in {@code [0, 36500]}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison filter — wire form {@code ?dte=<operator><days>} (e.g. {@code
   * ?dte=>=30}). Matches every expiration satisfying {@code operator days}. {@code days} must be in
   * {@code [0, 36500]}. Syntax mirrors {@link StrikeFilter#comparison}.
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

  /**
   * Validated in the compact constructor (not just {@link #dte(int...)}) so the guarantee holds on
   * every public construction path, including a direct {@code new DteList(...)} call.
   */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (Integer d : days) {
        if (d == null) {
          throw new IllegalArgumentException("days must not contain null elements");
        }
        requireDteInRange(d);
      }
      days = List.copyOf(days);
    }
  }

  /** Validated in the compact constructor — see {@link DteList}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
      requireDteInRange(min);
      requireDteInRange(max);
    }
  }

  /** Validated in the compact constructor — see {@link DteList}. */
  record DteComparison(StrikeFilter.Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      requireDteInRange(days);
    }
  }

  /** Shared {@code [0, 36500]} bound — the API's documented limit on every {@code dte} value. */
  private static void requireDteInRange(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException("dte must be at most 36500");
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}
}
