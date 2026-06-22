package com.marketdata.sdk;

/**
 * Builds a typed {@link MarketDataResponse} from the decoded payload, the response envelope, and
 * the sent {@link Format}. Single package-private SAM shared by every typed resource (each endpoint
 * supplies a {@code (decoded, env, fmt) -> new XxxResponse(...)} lambda); the parallel text-format
 * facets use {@link TextResponses.Factory} instead, since their payload is a raw string.
 *
 * @param <D> the decoded wire type Jackson produces (e.g. {@code StockCandles})
 * @param <R> the public response type returned to the consumer (e.g. {@code StockCandlesResponse})
 */
@FunctionalInterface
interface ResponseFactory<D, R> {
  R create(D decoded, HttpResponseEnvelope envelope, Format format);
}
