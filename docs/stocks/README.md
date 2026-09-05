# Stocks (Java SDK)

The Java SDK from Market Data provides methods to streamline your use of the Stocks endpoints. These methods provide a typed interface over the underlying HTTP requests and responses, with both sync and async variants.

Reach the resource through `client.stocks()`. For CSV output use `client.stocks().asCsv()`.

## Stocks Endpoints

- [Stock Candles (Java SDK)](./candles.md) — Retrieve historical open, high, low, close and volume candles for a stock with the Java SDK StockCandlesRequest and its resolution options.
- [Stock Quotes (Java SDK)](./quotes.md) — Retrieve real-time bid, ask, mid, last and volume for one or more stock symbols with the Java SDK, in single or multi-symbol request form.
- [Prices (Java SDK)](./prices.md) — Retrieve the latest mid price and change for one or more stock symbols with the Java SDK StockPricesRequest, a lighter payload than a quote.
- [News (Java SDK)](./news.md) — Retrieve news articles for a stock symbol with the Java SDK, building the query with a StockNewsRequest object.
- [Earnings (Java SDK)](./earnings.md)
