package com.marketdata.sdk;

import java.time.ZonedDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code options.expirations}: {@link #values()} is the available expiration dates.
 * The endpoint also carries an {@code updated} timestamp, exposed via {@link #updated()} ({@code
 * null} on the {@code no_data} envelope). Construct only through the resource façade.
 */
public final class OptionsExpirationsResponse
    extends AbstractMarketDataResponse<List<ZonedDateTime>> {

  private final @Nullable ZonedDateTime updated;

  OptionsExpirationsResponse(
      List<ZonedDateTime> values,
      @Nullable ZonedDateTime updated,
      HttpResponseEnvelope envelope,
      Format format) {
    super(values, envelope, format);
    this.updated = updated;
  }

  /** When the expiration list was last refreshed, or {@code null} when the API omitted it. */
  public @Nullable ZonedDateTime updated() {
    return updated;
  }
}
