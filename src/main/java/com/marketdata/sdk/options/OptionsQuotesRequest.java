package com.marketdata.sdk.options;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for the multi-contract form of {@code /v1/options/quotes/}. Carries a list of one or
 * more OCC option symbols plus the optional historical-window filters shared across them. The
 * resource fans out one HTTP request per symbol concurrently (via the SDK's 50-permit {@code
 * AsyncSemaphore}) and returns a {@code Map<String, Response<OptionsQuotes>>} so per-symbol status,
 * raw body, and error envelopes stay observable.
 *
 * <p>For a single contract, prefer {@link OptionsQuoteRequest} — clearer intent and one fewer map
 * lookup at the call site.
 */
public final class OptionsQuotesRequest {

  private final List<String> optionSymbols;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;

  private OptionsQuotesRequest(Builder b) {
    this.optionSymbols = List.copyOf(b.optionSymbols);
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
  }

  /**
   * Start a builder with one or more option symbols. At least one symbol is required; duplicates
   * are kept (each one results in its own HTTP call).
   */
  public static Builder builder(String first, String... rest) {
    Builder b = new Builder();
    b.addSymbol(first);
    for (String s : rest) {
      b.addSymbol(s);
    }
    return b;
  }

  public List<String> optionSymbols() {
    return optionSymbols;
  }

  public @Nullable LocalDate date() {
    return date;
  }

  public @Nullable LocalDate from() {
    return from;
  }

  public @Nullable LocalDate to() {
    return to;
  }

  public static final class Builder {
    private final List<String> optionSymbols = new ArrayList<>();
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;

    private Builder() {}

    public Builder addSymbol(String optionSymbol) {
      Objects.requireNonNull(optionSymbol, "optionSymbol");
      if (optionSymbol.isEmpty()) {
        throw new IllegalArgumentException("optionSymbol must be non-empty");
      }
      this.optionSymbols.add(optionSymbol);
      return this;
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

    public OptionsQuotesRequest build() {
      if (optionSymbols.isEmpty()) {
        throw new IllegalArgumentException("at least one optionSymbol is required");
      }
      if (date != null && (from != null || to != null)) {
        throw new IllegalArgumentException("date and from/to are mutually exclusive");
      }
      return new OptionsQuotesRequest(this);
    }
  }
}
