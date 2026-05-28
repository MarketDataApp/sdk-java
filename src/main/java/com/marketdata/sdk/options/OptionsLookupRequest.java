package com.marketdata.sdk.options;

import java.util.Objects;

/**
 * Parameters for {@code GET /v1/options/lookup/{userInput}/}. The endpoint takes a single
 * path-positional value (the human-readable description) and no query parameters beyond the §3
 * universal set; the request class exists for SDK-wide consistency so every endpoint is reached the
 * same way ({@code options.lookup(request)}).
 *
 * <p>Constructed via {@link #of(String)} when only the required {@code userInput} is needed, or
 * {@link #builder(String)} when forward-compatible with future optional fields.
 */
public final class OptionsLookupRequest {

  private final String userInput;

  private OptionsLookupRequest(Builder b) {
    this.userInput = b.userInput;
  }

  /** Shortcut for {@code builder(userInput).build()}. */
  public static OptionsLookupRequest of(String userInput) {
    return builder(userInput).build();
  }

  /** Start a builder seeded with the required path field. */
  public static Builder builder(String userInput) {
    return new Builder(userInput);
  }

  /** The human-readable option description, e.g. {@code "AAPL 7/26/23 $200 Call"}. */
  public String userInput() {
    return userInput;
  }

  /** Mutable builder; each chain produces a new {@link OptionsLookupRequest} via {@link #build}. */
  public static final class Builder {
    private final String userInput;

    private Builder(String userInput) {
      this.userInput = Objects.requireNonNull(userInput, "userInput");
    }

    public OptionsLookupRequest build() {
      if (userInput.isEmpty()) {
        throw new IllegalArgumentException("userInput must be non-empty");
      }
      return new OptionsLookupRequest(this);
    }
  }
}
