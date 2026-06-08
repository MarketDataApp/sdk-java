package com.marketdata.sdk;

import com.marketdata.sdk.options.ExpirationStrikes;
import java.time.ZonedDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code options.strikes}: {@link #values()} is the per-expiration strike lists. The
 * endpoint also carries an {@code updated} timestamp, exposed via {@link #updated()} ({@code null}
 * on the {@code no_data} envelope). Construct only through the resource façade.
 */
public final class OptionsStrikesResponse
    extends AbstractMarketDataResponse<List<ExpirationStrikes>> {

  private final @Nullable ZonedDateTime updated;

  OptionsStrikesResponse(
      List<ExpirationStrikes> values,
      @Nullable ZonedDateTime updated,
      HttpResponseEnvelope envelope,
      Format format) {
    super(values, envelope, format);
    this.updated = updated;
  }

  /** When the strike table was last refreshed, or {@code null} when the API omitted it. */
  public @Nullable ZonedDateTime updated() {
    return updated;
  }
}
