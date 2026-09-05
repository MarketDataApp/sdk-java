# Headers (Java SDK)

Echo back the HTTP headers the server received for your request — useful for confirming your `Authorization` header actually reached the API. Sensitive values are redacted in the response.

## Making Requests

Use the `headers()` method on the `utilities` resource. It requires a valid token.

```java
UtilitiesHeadersResponse headers()
CompletableFuture<UtilitiesHeadersResponse> headersAsync()
```

### Returns

`UtilitiesHeadersResponse` wrapping `Map<String, String>` — the headers the server received, lower-cased keys to values (sensitive values redacted server-side).

## Examples

### Java

```java
import com.marketdata.sdk.MarketDataClient;

try (MarketDataClient client = new MarketDataClient()) {
  var headers = client.utilities().headers().values();
  System.out.println("Server saw " + headers.size() + " request headers");
}
```

### Kotlin

```kotlin
import com.marketdata.sdk.MarketDataClient

MarketDataClient().use { client ->
    val headers = client.utilities().headers().values()
    println("Server saw ${headers.size} request headers")
}
```
