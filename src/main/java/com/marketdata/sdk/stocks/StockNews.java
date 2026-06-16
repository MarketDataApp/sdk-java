package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Decoded body of {@code GET /v1/stocks/news/{symbol}/}. The article fields are per-row arrays;
 * {@code updated} is a <em>scalar</em> at the response root — the single most-recent update time
 * for the live feed. The backend omits {@code updated} for date-bounded (historical) queries, so it
 * is {@code @Nullable} here.
 *
 * @param articles the article rows; immutable, never {@code null}, empty for a {@code
 *     "s":"no_data"} body.
 * @param updated the feed's latest update time, or {@code null} for historical queries.
 */
public record StockNews(List<StockNewsArticle> articles, @Nullable ZonedDateTime updated) {

  public StockNews {
    Objects.requireNonNull(articles, "articles");
    articles = List.copyOf(articles);
  }
}
