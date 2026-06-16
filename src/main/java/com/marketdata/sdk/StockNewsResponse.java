package com.marketdata.sdk;

import com.marketdata.sdk.stocks.StockNewsArticle;
import java.time.ZonedDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code stocks.news}: {@link #values()} is the article rows. The feed's scalar {@code
 * updated} time is exposed separately via {@link #updated()} (it sits at the response root, not on
 * each row, and is absent for historical/date-bounded queries). Construct only through the resource
 * façade.
 */
public final class StockNewsResponse extends AbstractMarketDataResponse<List<StockNewsArticle>> {

  private final @Nullable ZonedDateTime updated;

  StockNewsResponse(
      List<StockNewsArticle> values,
      @Nullable ZonedDateTime updated,
      HttpResponseEnvelope envelope,
      Format format) {
    super(values, envelope, format);
    this.updated = updated;
  }

  /** The feed's latest update time, or {@code null} for historical (date-bounded) queries. */
  public @Nullable ZonedDateTime updated() {
    return updated;
  }
}
