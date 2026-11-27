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
    .expirationFilter(ExpirationFilter.dte(int days))                    // days-to-expiration
    .expirationFilter(ExpirationFilter.dteList(int... days))             // e.g. 15,30,45
    .expirationFilter(ExpirationFilter.dteRange(int minDays, int maxDays)) // closed range, e.g. 0-45
    .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator op, int days)) // e.g. >=30
    .expirationFilter(ExpirationFilter.between(LocalDate from, LocalDate to))
    .expirationFilter(ExpirationFilter.all())                  // every expiration
    // (if omitted, the API narrows to the front month)

    // Strike selection (sealed StrikeFilter — pick one):
    .strikeFilter(StrikeFilter.exact(double price))
    .strikeFilter(StrikeFilter.range(double min, double max))
    .strikeFilter(StrikeFilter.comparison(StrikeFilter.Operator op, double price))

    // Additive filters:
    .side(OptionSide.CALL)              // CALL or PUT
    .strikeRange(StrikeRange.ITM)       // ITM / OTM / ATM
    .strikeLimit(int limit)             // max strikes per side
    .delta(double delta)
    .minOpenInterest(long oi)
    .minVolume(long vol)
    .date(LocalDate date)               // historical chain
    .model(PricingModel model)          // BSM / BJS / CRR / BAW — pricing model for iv/greeks on a historical (date) request
    .build()
```

#### Pricing model (`model`)

`model(PricingModel)` picks the pricing model used for `iv` and the greeks, but only on a
historical request (paired with `date`) — the API rejects a `model` sent without `date` with a
400, and the filter is gated to paid plans (a trial gets a 402). `PricingModel.BSM`
(Black-Scholes-Merton) prices every contract; `BJS` (Bjerksund-Stensland), `CRR`
(Cox-Ross-Rubinstein) and `BAW` (Barone-Adesi-Whaley) price the American contracts (the European
contracts keep `BSM` regardless of which of the three is named). Leaving `model` unset defaults
American contracts to `BJS` and European contracts to `BSM`, and applies broker conventions on top
(half-ask pricing for a contract with no bid, the greeks of an option without time value for a
contract at or below the model's floor price, expiration-day greeks, and delta clamped to
`[-1, 1]`); naming any of the four models explicitly skips those conventions and returns the
model's own output instead — `null` for a contract that would have gotten the zero-time-value
treatment, and an unclamped delta.

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
import com.marketdata.sdk.options.PricingModel;
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

  // dte's other forms: a comma-separated list, a closed range, and a comparison — the same
  // syntax as strike's range()/comparison(). The range is inclusive at both ends. LT (<) and
  // GT (>) are strict and exclude the boundary day; LTE (<=) and GTE (>=) include it.
  var dteList = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteList(15, 30, 45))
          .build());
  var dteRange = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteRange(0, 45))
          .build());
  var dteComparison = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .expirationFilter(ExpirationFilter.dteComparison(StrikeFilter.Operator.GTE, 30))
          .build());

  // Pricing model on a historical chain — model requires date.
  var priced = client.options().chain(
      OptionsChainRequest.builder("AAPL")
          .date(java.time.LocalDate.of(2024, 1, 17))
          .model(PricingModel.CRR)
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
