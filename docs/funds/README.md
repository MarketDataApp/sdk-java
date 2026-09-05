# Funds (Java SDK)

The Java SDK from Market Data provides methods to streamline your use of the Funds endpoints. These methods provide a typed interface over the underlying HTTP requests and responses, with both sync and async variants.

Reach the resource through `client.funds()`. For CSV output use `client.funds().asCsv()`.

## Funds Endpoints

- [Fund Candles (Java SDK)](./candles.md) — Retrieve a mutual fund net asset value OHLC series with the Java SDK FundCandlesRequest, choosing the resolution that you need.
