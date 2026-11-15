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
   * Days-to-expiration, comma-separated list — wire form {@code ?dte=15,30,45}. Selects the
   * expiration closest to each value, deduplicated server-side. Each value must be non-negative and
   * at most 36500 days, per the API.
   */
  static DteList dteList(int... days) {
    Objects.requireNonNull(days, "days");
    List<Integer> boxed = new ArrayList<>(days.length);
    for (int day : days) {
      boxed.add(day);
    }
    return new DteList(boxed);
  }

  /**
   * Days-to-expiration, closed inclusive range — wire form {@code ?dte=0-45}. Returns every
   * expiration whose dte falls within {@code [minDays, maxDays]}. {@code minDays} must not exceed
   * {@code maxDays}; both must be non-negative and at most 36500 days, per the API. The syntax
   * matches {@link StrikeFilter#range}.
   */
  static DteRange dteRange(int minDays, int maxDays) {
    return new DteRange(minDays, maxDays);
  }

  /**
   * Days-to-expiration, comparison — wire form {@code ?dte=>=30} or {@code ?dte=<45}. Returns every
   * expiration whose dte satisfies {@code operator days}, inclusive where the operator is. {@code
   * days} must be non-negative and at most 36500, per the API. The syntax matches {@link
   * StrikeFilter#comparison}, and reuses its {@link StrikeFilter.Operator}.
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

  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("days must not be empty");
      }
      for (Integer day : days) {
        Objects.requireNonNull(day, "days element");
        checkDteBounds(day, "days element");
      }
      days = List.copyOf(days);
    }
  }

  record DteRange(int minDays, int maxDays) implements ExpirationFilter {
    public DteRange {
      checkDteBounds(minDays, "minDays");
      checkDteBounds(maxDays, "maxDays");
      if (minDays > maxDays) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  record DteComparison(StrikeFilter.Operator operator, int days) implements ExpirationFilter {
    public DteComparison {
      Objects.requireNonNull(operator, "operator");
      checkDteBounds(days, "days");
    }
  }

  /** Shared bound for every dte form, per the API: "whole numbers of days, at most 36500." */
  private static void checkDteBounds(int days, String label) {
    if (days < 0) {
      throw new IllegalArgumentException(label + " must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException(label + " must be at most 36500");
    }
  }

  record Between(LocalDate from, LocalDate to) implements ExpirationFilter {}

  record MonthYear(int year, int month) implements ExpirationFilter {}

  /** The whole chain across every expiration — see {@link #all()}. Carries no data of its own. */
  record All() implements ExpirationFilter {}
}
