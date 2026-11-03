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
    .expirationFilter(ExpirationFilter.dte(int days))                       // a single dte value
    .expirationFilter(ExpirationFilter.dteList(List<Integer> days))         // comma-separated dte values
    .expirationFilter(ExpirationFilter.dteRange(int min, int max))          // closed dte range, inclusive
    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator op, int days)) // dte comparison
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

`dte` itself accepts four forms — a single value, a comma-separated list, a closed range, or a
comparison — the same range/comparison syntax `StrikeFilter` uses for `strike`:

```java
ExpirationFilter.dte(30)                                            // ?dte=30
ExpirationFilter.dteList(List.of(15, 30, 45))                       // ?dte=15,30,45
ExpirationFilter.dteRange(0, 45)                                    // ?dte=0-45
ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, 30)       // ?dte=>=30
```

Every `dte` value accepted by the API is a non-negative whole number of days, at most 36500. The
SDK enforces that upper bound client-side on the three new forms (`dteList`/`dteRange`/
`dteComparison`); the original single-value `dte(int)` predates the bound and still lets an
out-of-range value reach the API rather than rejecting it locally.

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

  // dte's own sub-syntax: list, closed range, and comparison, matching StrikeFilter's syntax.
  client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteList(List.of(15, 30, 45)))
          .build());
  client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteRange(0, 45))
          .build());
  client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, 30))
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
