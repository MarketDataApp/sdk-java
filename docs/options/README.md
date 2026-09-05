# Options (Java SDK)

The Java SDK from Market Data provides methods to streamline your use of the Options endpoints. These methods provide a typed interface over the underlying HTTP requests and responses, with both sync and async variants.

Reach the resource through `client.options()`. For CSV output use `client.options().asCsv()`.

## Options Endpoints

- [Lookup (Java SDK)](./lookup.md) — Turn a human-readable option description into a well-formed OCC option symbol with the Java SDK and its OptionsLookupRequest type.
- [Expirations (Java SDK)](./expirations.md) — List the available option expiration dates for an underlying symbol with the Java SDK and its OptionsExpirationsRequest type.
- [Option Quotes (Java SDK)](./quotes.md)
- [Option Chain (Java SDK)](./chain.md)
