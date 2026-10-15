# Option Chain (Java SDK)

Retrieve a full option chain for an underlying, with a rich set of filters. The chain is where most option workflows start.

## Making Requests

Use the `chain()` method on the `options` resource, built with `OptionsChainRequest`.

```java
OptionsChainResponse chain(OptionsChainRequest request)
CompletableFuture<OptionsChainResponse> chainAsync(OptionsChainRequest request)
```

### OptionsChainRequest

The chain has two mutually-exclusive filter axes modeled as **sealed types**, so the compiler lets you pick exactly one variant of each:

```java
OptionsChainRequest.builder(String symbol)

    // Expiration selection (sealed ExpirationFilter — pick one):
    .expirationFilter(ExpirationFilter.onDate(LocalDate date))
    .expirationFilter(ExpirationFilter.dte(int days))          // days-to-expiration, single value
    .expirationFilter(ExpirationFilter.dte(DteFilter filter))  // days-to-expiration, list/range/comparison
    .expirationFilter(ExpirationFilter.between(LocalDate from, LocalDate to))
    .expirationFilter(ExpirationFilter.all())                  // every expiration
    // (if omitted, the API narrows to the front month)

    // Strike selection (sealed StrikeFilter — pick one):
    .strikeFilter(StrikeFilter.exact(double price))
    .strikeFilter(StrikeFilter.range(double min, double max))

    // Additive filters:
    .side(OptionSide.CALL)              // CALL or PUT
    .strikeRange(StrikeRange.ITM)       // ITM / OTM / ATM
    .strikeLimit(int limit)             // max strikes per side
    .delta(double delta)
    .minOpenInterest(long oi)
    .minVolume(long vol)
    .date(LocalDate date)               // historical chain
    .build()
```

#### DteFilter

`ExpirationFilter.dte(DteFilter)` covers the `dte` query parameter's multi-value syntax — a sealed type alongside `ExpirationFilter.dte(int)`'s single-value form:

```java
DteFilter.list(int first, int... rest)             // comma-separated list, e.g. ?dte=15,30,45
DteFilter.range(int min, int max)                  // closed, inclusive range, e.g. ?dte=0-45
DteFilter.comparison(DteFilter.Operator op, int days) // e.g. ?dte=>=30 — GT, GTE, LT, LTE
```

Every value, in every form, must be a whole number of days in `[0, 36500]`.

<a name="optionquote"></a>
#### Returns

`OptionsChainResponse` wrapping `List<OptionQuote>`:

```java
public record OptionQuote(
    @Nullable String optionSymbol, @Nullable String underlying,
    @Nullable ZonedDateTime expiration, @Nullable String side, @Nullable Double strike,
    @Nullable ZonedDateTime firstTraded, @Nullable Integer dte, @Nullable ZonedDateTime updated,
    @Nullable Double bid, @Nullable Long bidSize,
    @Nullable Double mid, @Nullable Double ask, @Nullable Long askSize,
    @Nullable Double last, @Nullable Long openInterest, @Nullable Long volume,
    @Nullable Boolean inTheMoney, @Nullable Double intrinsicValue, @Nullable Double extrinsicValue,
    @Nullable Double underlyingPrice, @Nullable Double iv,
    // greeks:
    @Nullable Double delta, @Nullable Double gamma, @Nullable Double theta,
    @Nullable Double vega, @Nullable Double rho)
```

## Examples

### Java

```java
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.options.DteFilter;
import com.marketdata.sdk.options.ExpirationFilter;
import com.marketdata.sdk.options.OptionQuote;
import com.marketdata.sdk.options.OptionSide;
import com.marketdata.sdk.options.OptionsChainRequest;
import com.marketdata.sdk.options.StrikeFilter;
import java.util.List;

try (MarketDataClient client = new MarketDataClient()) {

  // The 5 calls nearest the money.
  List<OptionQuote> chain = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .side(OptionSide.CALL)
          .strikeLimit(5)
          .build())
      .values();

  for (OptionQuote c : chain) {
    System.out.println(c.optionSymbol() + " strike=" + c.strike()
        + " bid/ask=" + c.bid() + "/" + c.ask() + " delta=" + c.delta());
  }

  // Sealed filters: contracts within 45 days, strikes between 150 and 250.
  var filtered = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dte(45))
          .strikeFilter(StrikeFilter.range(150, 250))
          .side(OptionSide.CALL)
          .build());

  // DteFilter: contracts between 30 and 60 days to expiry, inclusive.
  var byDteRange = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dte(DteFilter.range(30, 60)))
          .side(OptionSide.CALL)
          .build());
}
```

### Kotlin

```kotlin
import com.marketdata.sdk.MarketDataClient
import com.marketdata.sdk.options.OptionSide
import com.marketdata.sdk.options.OptionsChainRequest

MarketDataClient().use { client ->
    val chain = client.options().chain(
        OptionsChainRequest.builder("AAPL")
            .side(OptionSide.CALL)
            .strikeLimit(5)
            .build())
        .values()

    for (c in chain) {
        println("${c.optionSymbol()} strike=${c.strike()} delta=${c.delta()}")
    }
}
```
