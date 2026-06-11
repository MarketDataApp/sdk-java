package com.marketdata.sdk.markets;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for {@code GET /v1/markets/status/}. <em>Every</em> parameter is optional — a bare
 * request returns today's status for US exchanges; the window parameters select a single day
 * ({@code date}) or a range ({@code from}/{@code to}, or {@code to}+{@code countback}); {@code
 * country} switches the exchange calendar (two-digit ISO 3166; the backend currently serves US only
 * and answers {@code no_data} for others).
 *
 * <p>Window rules (enforced in {@link Builder#build()}): {@code date} is incompatible with {@code
 * from}/{@code to}/{@code countback}; {@code countback} pairs with {@code to} (not {@code from})
 * and must be positive.
 */
public final class MarketStatusRequest {

  private final @Nullable String country;
  private final @Nullable LocalDate date;
  private final @Nullable LocalDate from;
  private final @Nullable LocalDate to;
  private final @Nullable Integer countback;

  private MarketStatusRequest(Builder b) {
    this.country = b.country;
    this.date = b.date;
    this.from = b.from;
    this.to = b.to;
    this.countback = b.countback;
  }

  /** Shortcut for {@code builder().build()} — today's status, US calendar. */
  public static MarketStatusRequest of() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public @Nullable String country() {
    return country;
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

  public @Nullable Integer countback() {
    return countback;
  }

  public static final class Builder {
    private @Nullable String country;
    private @Nullable LocalDate date;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Integer countback;

    private Builder() {}

    /** Exchange-calendar country (two-digit ISO 3166 code). Backend default: {@code US}. */
    public Builder country(String country) {
      this.country = Objects.requireNonNull(country, "country");
      return this;
    }

    /** Look up the status of a single day. Exclusive with {@code from}/{@code to}/countback. */
    public Builder date(LocalDate date) {
      this.date = Objects.requireNonNull(date, "date");
      return this;
    }

    /** The first day of the range (inclusive). */
    public Builder from(LocalDate from) {
      this.from = Objects.requireNonNull(from, "from");
      return this;
    }

    /** The last day of the range (inclusive). */
    public Builder to(LocalDate to) {
      this.to = Objects.requireNonNull(to, "to");
      return this;
    }

    /** Fetch {@code countback} days before {@code to}. Positive; pair with {@code to}. */
    public Builder countback(int countback) {
      this.countback = countback;
      return this;
    }

    public MarketStatusRequest build() {
      MarketRequests.validateWindow(date, from, to, countback);
      return new MarketStatusRequest(this);
    }
  }
}
