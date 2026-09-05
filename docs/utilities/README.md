# Utilities (Java SDK)

The Java SDK from Market Data provides utility methods for checking API health, inspecting your account quota, and debugging requests. These methods provide a typed interface over the underlying HTTP requests and responses, with both sync and async variants.

Reach the resource through `client.utilities()`.

## Utilities Endpoints

- [API Status (Java SDK)](./status.md)
- [User (Java SDK)](./user.md) — Retrieve your Market Data account details with the Java SDK, including how much of the daily request quota is still available.
- [Headers (Java SDK)](./headers.md) — Echo back the headers the Market Data API received with the Java SDK, to confirm your Authorization header arrived. Sensitive values are redacted.
