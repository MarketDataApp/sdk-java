# Delete Token (Java SDK)

Revoke the token used to authenticate the current request.

> [!WARNING]
> This is **irreversible**: once the call succeeds, the token that made it can no longer
> authenticate any further request — including a retry of this one, and any other consumer
> sharing the same token. Generate a new token from the [Market Data Dashboard](https://www.marketdata.app/dashboard/)
> before making another authenticated call.

## Making Requests

Use the `deleteToken()` method on the `utilities` resource. It requires a valid token — the one it revokes.

```java
UtilitiesDeleteTokenResponse deleteToken()
CompletableFuture<UtilitiesDeleteTokenResponse> deleteTokenAsync()
```

### Returns

`UtilitiesDeleteTokenResponse` wrapping a `Boolean` — `true` once the server confirms the token was revoked.

## Examples

### Java

```java
import com.marketdata.sdk.MarketDataClient;

try (MarketDataClient client = new MarketDataClient("token-to-retire", null, null, true)) {
  boolean revoked = client.utilities().deleteToken().values();
  System.out.println("Token revoked: " + revoked);
}
```

### Kotlin

```kotlin
import com.marketdata.sdk.MarketDataClient

MarketDataClient("token-to-retire", null, null, true).use { client ->
    val revoked = client.utilities().deleteToken().values()
    println("Token revoked: $revoked")
}
```
