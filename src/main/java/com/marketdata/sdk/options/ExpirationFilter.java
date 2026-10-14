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
 * <p>All {@code dte}/{@code dteList}/{@code dteRange}/{@code dteComparison} variants count days
 * from the chain request's {@code date} parameter when one is set (a historical chain), or from
 * today otherwise.
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

  /**
   * Days-to-expiration, single value — wire form {@code ?dte=N}. Limits the option chain to the
   * expiration date closest to {@code days}.
   *
   * <p>The API's documented maximum is 36500. Unlike {@link #dteList(int, int...)}, {@link
   * #dteRange(int, int)}, and {@link #dteComparison(StrikeFilter.Operator, int)}, this factory does
   * not enforce that bound locally — a larger value is sent to the wire unchanged and left for the
   * API to reject.
   */
  static Dte dte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    return new Dte(days);
  }

  /**
   * Days-to-expiration, comma-separated list — wire form {@code ?dte=15,30,45}. Selects the closest
   * expiration for each value, deduplicated server-side. At least one value is required; each value
   * must be a non-negative whole number of days, at most 36500.
   */
  static DteList dteList(int first, int... rest) {
    List<Integer> days = new ArrayList<>(rest.length + 1);
    days.add(first);
    for (int d : rest) {
      days.add(d);
    }
    return new DteList(days);
  }

  /**
   * Days-to-expiration, closed range — wire form {@code ?dte=0-45}. Returns every expiration whose
   * dte falls within {@code [min, max]} inclusive. {@code min} must not exceed {@code max}.
   */
  static DteRange dteRange(int min, int max) {
    return new DteRange(min, max);
  }

  /**
   * Days-to-expiration, comparison — wire form {@code ?dte=>=30} (operator prefix + value). Returns
   * every expiration whose dte satisfies {@code operator days}. The operator set is shared with
   * {@link StrikeFilter.Operator} — the two parameters use the same comparison syntax.
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

  /** Validates a single dte value: a non-negative whole number of days, at most 36500. */
  private static int validateDte(int days) {
    if (days < 0) {
      throw new IllegalArgumentException("dte must be non-negative");
    }
    if (days > 36500) {
      throw new IllegalArgumentException("dte must be at most 36500");
    }
    return days;
  }

  record OnDate(LocalDate date) implements ExpirationFilter {
    public OnDate {
      Objects.requireNonNull(date, "date");
    }
  }

  record Dte(int days) implements ExpirationFilter {}

  /**
   * Comma-separated dte list — see {@link #dteList(int, int...)}. Validated on every construction
   * path, not only the static factory: {@code days} must be non-null, non-empty, and free of null
   * elements, and every value must be a non-negative whole number of days, at most 36500.
   */
  record DteList(List<Integer> days) implements ExpirationFilter {
    public DteList {
      Objects.requireNonNull(days, "days");
      if (days.isEmpty()) {
        throw new IllegalArgumentException("dte list must not be empty");
      }
      for (Integer d : days) {
        Objects.requireNonNull(d, "dte list must not contain null elements");
        validateDte(d);
      }
      days = List.copyOf(days);
    }
  }

  /** Closed dte range — see {@link #dteRange(int, int)}. */
  record DteRange(int min, int max) implements ExpirationFilter {
    public DteRange {
      validateDte(min);
      validateDte(max);
      if (min > max) {
        throw new IllegalArgumentException("min must be <= max");
      }
    }
  }

  /** Dte comparison — see {@link #dteComparison(StrikeFilter.Operator, int)}. */
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
}
