package com.marketdata.sdk.options;

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
 *
 * <p>{@code dte} itself carries its own sub-syntax ({@link #dte}, {@link #dteList}, {@link
 * #dteRange}, {@link #dteComparison}) — a single value, a comma-separated list, a closed range, or
 * a comparison, matching the syntax {@link StrikeFilter} uses for {@code strike}. These are
 * separate permitted variants rather than a nested sealed type because they all serialize to the
 * same {@code dte} query parameter and already live under this sealed hierarchy's "pick one
 * variant" umbrella.
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
   * closest to each value in {@code days}; the API deduplicates server-side when two values resolve
   * to the same expiration. Each value is bounded like {@link #dte} ({@code [0, 36500]}).
   */
  static DteList dteList(List<Integer> days) {
    return new DteList(days);
  }

  /**
   * Days-to-expiration closed-range filter — wire form {@code ?dte=min-max}. Returns every
   * expiration whose dte falls within {@code [min, max]} inclusive; the syntax matches {@link
   * StrikeFilter#range}. {@code min} must not exceed {@code max}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison filter — wire form e.g. {@code ?dte=>=30}. Returns every
   * expiration satisfying {@code operator days}; the syntax matches {@link
   * StrikeFilter#comparison}.
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

  /** The comma-separated-list form of {@code dte} — see {@link #dteList}. */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      days = List.copyOf(days); // defensive copy; rejects a null element with NullPointerException
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (int d : days) {
        checkDteBounds(d);
      }
    }
  }

  /** The closed-range form of {@code dte} — see {@link #dteRange}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      checkDteBounds(min);
      checkDteBounds(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /** The comparison-operator form of {@code dte} — see {@link #dteComparison}. */
  record DteComparison(StrikeFilter.Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      checkDteBounds(days);
    }
  }

  /** Shared {@code dte} bound — whole numbers of days, at most 36500, per the API's description. */
  private static void checkDteBounds(int days) {
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
