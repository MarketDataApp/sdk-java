# User

Retrieve your account details — including how much of your daily request quota remains. This is the same endpoint the SDK calls on startup to validate your token.

## Making Requests

Use the `user()` method on the `utilities` resource. It requires a valid token.

```java
UtilitiesUserResponse user()
CompletableFuture<UtilitiesUserResponse> userAsync()
```

#### Returns

`UtilitiesUserResponse` wrapping a `User`, which exposes your plan and quota — for example `requestsRemaining()` and `requestsLimit()`.

## Examples

### Java

```java
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.utilities.User;

try (MarketDataClient client = new MarketDataClient()) {
  User me = client.utilities().user().values();
  System.out.println("Quota: " + me.requestsRemaining() + " of "
      + me.requestsLimit() + " requests left today");
}
```

### Kotlin

```kotlin
import com.marketdata.sdk.MarketDataClient

MarketDataClient().use { client ->
    val me = client.utilities().user().values()
    println("Quota: ${me.requestsRemaining()} of ${me.requestsLimit()} left today")
}
```
