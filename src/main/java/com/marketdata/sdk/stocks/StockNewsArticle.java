package com.marketdata.sdk.stocks;

import java.time.ZonedDateTime;

/**
 * A single news article — one row of {@link StockNews}. Fields are non-null: the backend always
 * emits every column for a normal article row (a {@code "s":"no_data"} body carries no rows at
 * all).
 *
 * @param symbol the ticker the article is about.
 * @param headline the article headline.
 * @param content the article body/summary.
 * @param source the article URL.
 * @param publicationDate the publication date, lifted to a market-zone moment.
 */
public record StockNewsArticle(
    String symbol, String headline, String content, String source, ZonedDateTime publicationDate) {}
