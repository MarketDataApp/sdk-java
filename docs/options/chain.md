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
    .expirationFilter(ExpirationFilter.dte(int days))                       // days-to-expiration
    .expirationFilter(ExpirationFilter.dteList(List<Integer> days))         // closest expiration per value
    .expirationFilter(ExpirationFilter.dteRange(int min, int max))          // every expiration within [min, max]
    .expirationFilter(ExpirationFilter.dteComparison(Operator op, int days)) // every expiration satisfying "op days"
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

`dte` accepts four forms: a single value (`dte(int)`), a comma-separated list (`dteList(List<Integer>)` — for each value, the API selects its closest available expiration), a closed range (`dteRange(int, int)`, both ends inclusive — same grammar as `StrikeFilter.range`), and a comparison (`dteComparison(ExpirationFilter.Operator, int)`, operators `GT`/`GTE`/`LT`/`LTE` — same grammar as `StrikeFilter.comparison`). Every `dte` value, in any of the four forms, is relative to the request's `date` parameter when one is set (to "today" otherwise), and is capped at 36,500 days (100 years).

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

  // dte also accepts a list, a closed range, or a comparison.
  var dteRange = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteRange(0, 45))
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
