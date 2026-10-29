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
 * <p>{@code dte} alone carries four wire syntaxes — a single value ({@link #dte(int)}), a
 * comma-separated list ({@link #dteList(List)}), a closed range ({@link #dteRange(int, int)}), and
 * a comparison ({@link #dteComparison(Operator, int)}) — mirroring {@link StrikeFilter}'s
 * exact/range/comparison forms plus the list form the API allows only for {@code dte}.
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

  /** Smallest number of days to expiry the API accepts for any {@code dte} form. */
  int MIN_DTE = 0;

  /** Largest number of days to expiry the API accepts for any {@code dte} form. */
  int MAX_DTE = 36500;

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
   * Days-to-expiration filter — wire form {@code ?dte=N}. Limits the option chain to the expiration
   * date closest to {@code days}.
   */
  static Dte dte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    return new Dte(days);
  }

  /**
   * Days-to-expiration list — wire form {@code ?dte=15,30,45}. Selects, for each value in {@code
   * days}, the expiration closest to it; the API deduplicates the result. Each value must be in
   * {@code [0, 36500]}.
   */
  static DteList dteList(List<Integer> days) {
    return new DteList(days);
  }

  /**
   * Days-to-expiration closed range — wire form {@code ?dte=0-45}. Returns every expiration whose
   * dte falls within {@code [min, max]}, inclusive. {@code min} must not exceed {@code max}, and
   * both must be in {@code [0, 36500]}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration comparison — wire form {@code ?dte=>=30} (e.g. {@code operator} {@code GTE},
   * {@code days} {@code 30}). Returns every expiration satisfying {@code operator days}. {@code
   * days} must be in {@code [0, 36500]}.
   */
  static DteComparison dteComparison(Operator operator, int days) {
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

  /**
   * Comparison operators accepted by the {@code dte} parameter — mirrors {@link StrikeFilter}'s.
   */
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
   * Rejects a {@code dte} value outside {@code [0, 36500]} — shared by every new {@code dte} form.
   */
  private static void checkDteBounds(int days) {
    if (days < MIN_DTE || days > MAX_DTE) {
      throw new IllegalArgumentException("dte must be in [0, 36500]");
    }
  }

  record OnDate(LocalDate date) implements ExpirationFilter {
    public OnDate {
      Objects.requireNonNull(date, "date");
    }
  }

  record Dte(int days) implements ExpirationFilter {}

  /** See {@link #dteList(List)}. */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (Integer day : days) {
        Objects.requireNonNull(day, "days must not contain null elements");
        checkDteBounds(day);
      }
      days = List.copyOf(days);
    }
  }

  /** See {@link #dteRange(int, int)}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      checkDteBounds(min);
      checkDteBounds(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /** See {@link #dteComparison(Operator, int)}. */
  record DteComparison(Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      checkDteBounds(days);
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}
}
