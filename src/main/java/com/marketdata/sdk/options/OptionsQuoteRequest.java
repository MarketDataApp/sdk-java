package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for the single-contract form of {@code GET /v1/options/quotes/{optionSymbol}/}. One
 * OCC-formatted option symbol plus the optional historical-window filters ({@code date}, {@code
 * from}/{@code to}, or {@code to}/{@code countback}).
 *
 * <p>For multiple contracts use {@link OptionsQuotesRequest} — the multi-symbol API fans out one
 * request per symbol concurrently and returns a {@code Map<String, OptionsQuotesResponse>}.
 */
public final class OptionsQuoteRequest {

  private final String optionSymbol;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;

  private OptionsQuoteRequest(Builder b) {
    this.optionSymbol = b.optionSymbol;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
  }

  /** Shortcut for {@code builder(optionSymbol).build()}. */
  public static OptionsQuoteRequest of(String optionSymbol) {
    return builder(optionSymbol).build();
  }

  public static Builder builder(String optionSymbol) {
    return new Builder(optionSymbol);
  }

  public String optionSymbol() {
    return optionSymbol;
  }

  /** End-of-day historical quote on a specific date, or {@code null} for the current quote. */
  public @Nullable LocalDate date() {
    return date;
  }

  /** Start of a date range (inclusive), or {@code null} when unset. */
  public @Nullable LocalDate from() {
    return from;
  }

  /** End of a date range (exclusive), or {@code null} when unset. */
  public @Nullable LocalDate to() {
    return to;
  }

  /**
   * Number of quotes to fetch before {@code to} (to its left), or {@code null} when unset. An
   * alternative to {@code from} for bounding the left edge of the window.
   */
  public @Nullable Integer countback() {
    return countback;
  }

  public static final class Builder {
    private final String optionSymbol;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;

    private Builder(String optionSymbol) {
      this.optionSymbol = Objects.requireNonNull(optionSymbol, "optionSymbol");
    }

    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    public Builder from(LocalDate from) {
      this.from = Objects.requireNonNull(from, "from");
      return this;
    }

    public Builder to(LocalDate to) {
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /**
     * Fetch {@code countback} quotes before {@code to}. Must be positive; mutually exclusive with
     * {@code date} and with {@code from} (per the API: "if you use from, countback is not
     * required"). Pair it with {@code to} to anchor the window.
     */
    public Builder countback(int countback) {
      this.countback = countback;
      return this;
    }

    public OptionsQuoteRequest build() {
      if (optionSymbol.isEmpty()) {
        throw new IllegalArgumentException("optionSymbol must be non-empty");
      }
      validateWindow(date, from, to, countback);
      return new OptionsQuoteRequest(this);
    }
  }

  /**
   * Shared validation for the historical-window parameters across both quote request forms: {@code
   * date} is a single snapshot incompatible with any ranging parameter; {@code countback} is an
   * alternative to {@code from} for the left edge, so the two cannot be combined; {@code countback}
   * must be positive; and {@code from} must not be after {@code to}.
   */
  static void validateWindow(
      @Nullable LocalDate date,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable Integer countback) {
    if (date != null && (from != null || to != null || countback != null)) {
      throw new IllegalArgumentException("date and from/to/countback are mutually exclusive");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
    if (countback != null) {
      if (countback <= 0) {
        throw new IllegalArgumentException("countback must be positive");
      }
      if (from != null) {
        throw new IllegalArgumentException(
            "countback and from are mutually exclusive; pair countback with to");
      }
    }
  }
}
