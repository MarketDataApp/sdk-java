# Status

Retrieve the exchange open/closed calendar — whether the market was (or will be) open on a given day.

> [!NOTE]
> This is distinct from [`utilities().status()`](../utilities/status.md), which reports the Market Data **API's** own service health.

## Making Requests

Use the `status()` method on the `markets` resource, built with `MarketStatusRequest`. Every parameter is optional; a bare request returns today's status for the US market.

```java
MarketStatusResponse status(MarketStatusRequest request)
CompletableFuture<MarketStatusResponse> statusAsync(MarketStatusRequest request)
```

### MarketStatusRequest

```java
MarketStatusRequest.of()              // today, US
MarketStatusRequest.builder()
    .country(String country)          // ISO 3166, 2-letter (default: US)
    .date(LocalDate date)             // a single day
    .from(LocalDate from)             // range start (inclusive)
    .to(LocalDate to)                 // range end (inclusive)
    .countback(int n)                 // N days before `to`
    .build()
```

#### Returns

`MarketStatusResponse` wrapping `List<MarketStatus>`:

```java
public record MarketStatus(
    @Nullable ZonedDateTime date,
    @Nullable String status)          // "open" / "closed" / null (outside coverage)
// also: boolean isOpen()
```

## Examples

### Java

```java
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.markets.MarketStatus;
import com.marketdata.sdk.markets.MarketStatusRequest;
import java.time.LocalDate;

try (MarketDataClient client = new MarketDataClient()) {
  var status = client.markets().status(
      MarketStatusRequest.builder()
          .from(LocalDate.now().minusDays(7))
          .to(LocalDate.now())
          .build());

  for (MarketStatus day : status.values()) {
    System.out.println(day.date().toLocalDate() + "  " + day.status()
        + (day.isOpen() ? "  (trading)" : ""));
  }
}
```

### Kotlin

```kotlin
import com.marketdata.sdk.MarketDataClient
import com.marketdata.sdk.markets.MarketStatusRequest
import java.time.LocalDate

MarketDataClient().use { client ->
    val status = client.markets().status(
        MarketStatusRequest.builder()
            .from(LocalDate.now().minusDays(7))
            .to(LocalDate.now())
            .build())

    for (day in status.values()) {
        println("${day.date().toLocalDate()}  ${day.status()}")
    }
}
```
