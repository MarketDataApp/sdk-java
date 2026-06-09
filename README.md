# Market Data Java SDK

Java SDK for the [Market Data API](https://www.marketdata.app/). **Pre-release**
— the `utilities`, `options`, and `stocks` resources are implemented; `funds`
and `markets` are forthcoming. The build, package layout, configuration cascade,
exception taxonomy, and Kotlin-interop foundations are in place.

## Requirements

- **JDK 17 or newer**. The published artifact is compiled with
  `javac --release 17`. Tests run on JDK 17, 21, and 25.
- **Jackson 2.18+** on the runtime classpath. Pulled transitively;
  consumers may align to a newer 2.x.

## Install (planned)

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.marketdata:marketdata-sdk-java:0.1.0")
}
```

Coordinates are placeholders until the first publication to Maven Central.

## Quick start

The SDK reads `MARKETDATA_TOKEN` from the environment by default, so the
common path is two lines:

### Java

```java
try (var client = new MarketDataClient()) {
    var resp = client.options().expirations(OptionsExpirationsRequest.of("AAPL"));
    System.out.println(resp.values());   // values() is the typed payload (a List<ZonedDateTime>)
}
```

### Kotlin

```kotlin
MarketDataClient().use { client ->
    val resp = client.options().expirations(OptionsExpirationsRequest.of("AAPL"))
    println(resp.values())   // List<ZonedDateTime>
}
```

Every response implements `MarketDataResponse<T>`: `values()` returns the typed payload
(typed per endpoint — a `List`, a scalar `String`, …), and the same metadata accessors
(`statusCode()`, `isNoData()`, `requestId()`, `json()`, `saveToFile(path)`) are available on
every response, on every resource.

## Options

Reached via `client.options()`. Every endpoint has a synchronous method and an
`…Async` variant returning `CompletableFuture`, and takes a Builder-based
request object — there are no `String` convenience overloads, so the call shape
is uniform across the SDK regardless of how many parameters an endpoint has.

| Method | Purpose |
|--------|---------|
| `lookup` | Resolve a human description (`"AAPL 1/16/2026 $200 Call"`) to an OCC symbol |
| `expirations` | Expiration dates for an underlying |
| `strikes` | Strike ladder per expiration |
| `quote` | Quote for a single OCC option symbol |
| `quotes` | Quotes for many symbols — fans out concurrently, returns a per-symbol map |
| `chain` | Full option chain with the rich filter surface |

### Chain with filters

The `chain` request exposes the API's full filter set. Mutually-exclusive groups
(expiration, strike) are modeled as sealed types, so the compiler lets you pick
only one variant per group:

#### Java

```java
try (var client = new MarketDataClient()) {
    var resp = client.options().chain(
        OptionsChainRequest.builder("AAPL")
            .expirationFilter(ExpirationFilter.all())    // every expiration, not just front-month
            .strikeFilter(StrikeFilter.range(150, 200))  // 150 <= strike <= 200
            .side(OptionSide.CALL)
            .strikeLimit(5)
            .build());

    for (OptionQuote q : resp.values()) {                // values() is a List<OptionQuote>
        System.out.printf("%s  delta=%s  rho=%s%n",
            q.optionSymbol(), q.delta(), q.rho());       // delta/rho are @Nullable Double
    }
}
```

#### Kotlin

```kotlin
MarketDataClient().use { client ->
    val resp = client.options().chain(
        OptionsChainRequest.builder("AAPL")
            .expirationFilter(ExpirationFilter.all())
            .strikeFilter(StrikeFilter.range(150.0, 200.0))
            .side(OptionSide.CALL)
            .strikeLimit(5)
            .build()
    )
    resp.values().forEach { q ->
        println("${q.optionSymbol}  delta=${q.delta}  rho=${q.rho}") // delta/rho are nullable
    }
}
```

### Multiple quotes

`quotes` fans out one request per symbol concurrently and returns a
`Map<String, OptionsQuotesResponse>` keyed by the input symbol, so per-symbol
status and errors stay observable. `countback` caps the historical series to the
N most recent rows before `to`:

```java
Map<String, OptionsQuotesResponse> bySymbol = client.options().quotes(
    OptionsQuotesRequest.builder("AAPL250117C00150000", "AAPL250117P00150000")
        .to(LocalDate.now())
        .countback(5)   // at most 5 EOD rows per symbol, before `to`
        .build());

bySymbol.forEach((sym, resp) -> System.out.println(sym + " → " + resp.values().size() + " rows"));
```

### Universal parameters & CSV

Universal parameters are set fluently on the resource (an immutable configured value, so you
can "configure once, call many"); `columns` projects the response to a subset of fields, and
`asCsv()` selects a CSV view of any endpoint:

```java
// type-preserving universal params + column projection (typed):
var chain = client.options()
    .dateFormat(DateFormat.TIMESTAMP).mode(Mode.DELAYED).limit(50)
    .columns("optionSymbol", "strike", "delta")   // fields you don't request come back null
    .chain(OptionsChainRequest.of("AAPL"));

// CSV facet (adds human/headers, which only make sense for CSV):
CsvResponse csv = client.options().asCsv().columns("optionSymbol", "strike").chain(req);
csv.saveToFile(Path.of("aapl-chain.csv"));
```

With `columns`, a field you didn't request decodes to `null` (no error); a **required** field
you *did* request (or didn't project away) that the API omits raises a `ParseError` — so a
`null` never silently hides a dropped field.

## Stocks

Reached via `client.stocks()`. Same conventions as `options`: every endpoint has a
synchronous method and an `…Async` variant, takes a Builder-based request object, and returns
a typed `MarketDataResponse` (payload via `values()`). The universal-parameter setters
(`dateFormat`/`mode`/`limit`/`offset`/`columns`) and the `asCsv()` facet work identically.

| Method | Purpose |
|--------|---------|
| `candles` | Historical OHLCV candles for a symbol at a given `StockResolution` |
| `quote` | Real-time quote for a single symbol |
| `quotes` | Quotes for many symbols — batched in **one** request, one row per symbol |
| `prices` | Lightweight price snapshot (mid/change) for many symbols |
| `news` | Recent news articles for a symbol |
| `earnings` | EPS history and the forward earnings calendar |

> Unlike `options.quotes` (which fans out one request per contract and returns a per-symbol
> map), the stocks backend accepts a comma list in a single request — so `stocks.quotes` and
> `stocks.prices` return a single response with one row per symbol.

### Candles

`StockResolution` is a value type, not an enum — the API accepts an open-ended family of
resolutions, so use the factories (`DAILY`, `minutes(15)`, `hours(1)`, `days(2)`, …):

#### Java

```java
try (var client = new MarketDataClient()) {
    var resp = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.now().minusMonths(1))
            .to(LocalDate.now())
            .build());

    for (StockCandle c : resp.values()) {              // values() is a List<StockCandle>
        System.out.printf("%s  O=%s H=%s L=%s C=%s V=%s%n",
            c.time(), c.open(), c.high(), c.low(), c.close(), c.volume());
    }
}
```

#### Kotlin

```kotlin
MarketDataClient().use { client ->
    val resp = client.stocks().candles(
        StockCandlesRequest.builder(StockResolution.DAILY, "AAPL")
            .from(LocalDate.now().minusMonths(1))
            .to(LocalDate.now())
            .build()
    )
    resp.values().forEach { c ->
        println("${c.time}  O=${c.open} H=${c.high} L=${c.low} C=${c.close} V=${c.volume}")
    }
}
```

### Quotes, prices, news, earnings

```java
// Multi-symbol quote — one batched request, one row per symbol:
StockQuotesResponse q = client.stocks().quotes(
    StockQuotesRequest.builder("AAPL", "MSFT")
        .candle(true)    // opt-in OHLC columns
        .week52(true)    // opt-in 52-week high/low columns
        .build());

// News — articles plus the feed's latest-update time as a scalar off the response:
StockNewsResponse news = client.stocks().news(StockNewsRequest.of("AAPL"));
news.values().forEach(a -> System.out.println(a.publicationDate() + "  " + a.headline()));
System.out.println("feed updated: " + news.updated());

// Earnings — fundamentals and report fields are nullable on forward-quarter rows:
client.stocks().earnings(StockEarningsRequest.of("AAPL"))
    .values()
    .forEach(e -> System.out.println("FY" + e.fiscalYear() + " Q" + e.fiscalQuarter()
        + " reportedEPS=" + e.reportedEPS()));
```

Stock quote/price numeric fields are `@Nullable` (the backend nulls them for a closed or
illiquid market), and the OHLC / 52-week columns appear only when opted in via `candle` /
`week52`.

## Configuration

Values are resolved through this cascade (highest priority first):

1. Explicit constructor parameters — `apiKey`, `baseUrl`, `apiVersion`
   (passed to `new MarketDataClient(apiKey, baseUrl, apiVersion, validateOnStartup)`;
   the no-arg `new MarketDataClient()` skips this step and starts at #2)
2. Environment variables (table below)
3. `.env` file in the current working directory
4. Built-in defaults

### Environment variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `MARKETDATA_TOKEN` | API authentication token | (none — demo mode) |
| `MARKETDATA_BASE_URL` | API base URL | `https://api.marketdata.app` |
| `MARKETDATA_API_VERSION` | API version | `v1` |
| `MARKETDATA_LOGGING_LEVEL` | SDK logging level | `INFO` |
| `MARKETDATA_OUTPUT_FORMAT` | Default output format | (language default) |
| `MARKETDATA_DATE_FORMAT` | Default date format | `timestamp` |
| `MARKETDATA_COLUMNS` | Columns to include | (all) |
| `MARKETDATA_ADD_HEADERS` | Include headers in CSV | `true` |
| `MARKETDATA_USE_HUMAN_READABLE` | Human-readable field names | `false` |
| `MARKETDATA_MODE` | Data mode (live/cached/delayed) | `live` |

The corresponding per-call setters — `dateFormat`/`limit`/`offset`/`mode`/`columns` on the
resource, plus `human`/`headers` and `asCsv()` on the CSV facet — are exposed on `options`
and `stocks` today (and on every resource as it lands). Auto-applying these env-var values as request
*defaults* (`DATE_FORMAT`, `COLUMNS`, `ADD_HEADERS`, `USE_HUMAN_READABLE`, `MODE`,
`OUTPUT_FORMAT`) is still reserved.

### Demo mode

Building a client without a token (no explicit `apiKey()`, no env var, no
`.env` entry) puts the client in **demo mode**: the `Authorization` header
is omitted from outbound requests and the SDK logs a warning at INFO
level. Authenticated endpoints will fail. Use this for read-only, public
endpoints only.

## Error handling

All SDK errors extend the sealed [`MarketDataException`](src/main/java/com/marketdata/sdk/exception/MarketDataException.java)
hierarchy and carry support context (`requestId`, `requestUrl`,
`statusCode`, `timestamp`) plus a `getSupportInfo()` helper for support
tickets:

```java
try {
    // call endpoint method (forthcoming)
} catch (RateLimitError e) {
    System.err.println(e.getSupportInfo());
}
```

The seven permitted subtypes are `AuthenticationError`, `BadRequestError`,
`NotFoundError`, `RateLimitError`, `ServerError`, `NetworkError`, and
`ParseError`. The hierarchy is sealed so `switch` over the subtypes is
compile-time exhaustive.

## Build

The repo uses **Gradle (Kotlin DSL)** with a version catalog at
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

The Gradle wrapper is committed (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`),
so any JDK 17+ environment can build the project without a separate Gradle
install — the wrapper downloads the right Gradle version on first run.

```bash
./gradlew build               # compile + unit tests + spotless + jacoco
./gradlew test                # unit tests only
./gradlew spotlessApply       # auto-format
./gradlew jacocoTestReport    # coverage report → build/reports/jacoco/

# Integration tests hit the live API — gated by env var.
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest
```

On PRs, integration tests are not run automatically (live-API quota +
CI minutes). A reviewer with `write` access triggers them by posting a
slash-command on the **first line** of a PR comment:

- `/integrationtest` — JDK 17 only.
- `/integrationtestfull` — full matrix `{17, 21, 25}`.

The first-line rule means quoted replies (`> /integrationtest`) and
prose that merely mentions the command do not fire a run. Anything that
isn't an exact match is rejected before any request is made.

## Package layout

```
com.marketdata.sdk             # MarketDataClient, RateLimits, the resource façades
                               # (UtilitiesResource, OptionsResource, StocksResource,
                               # OptionsCsvResource, StocksCsvResource), and
                               # MarketDataResponse<T> + the named response types
                               # (OptionsChainResponse, StockCandlesResponse, CsvResponse, …)
                               # — public; Configuration, EnvVars, Tokens, Version are
                               # package-private and not part of the API
com.marketdata.sdk.options     # Options request builders + row records
                               # (OptionsChainRequest, OptionQuote, sealed
                               # ExpirationFilter / StrikeFilter, Greek, …)
com.marketdata.sdk.stocks      # Stocks request builders + row records
                               # (StockCandlesRequest, StockCandle, StockQuote,
                               # StockEarning, StockResolution, …)
com.marketdata.sdk.exception   # Sealed MarketDataException hierarchy + ErrorContext
```

Every public package is `@NullMarked` (JSpecify): non-null is the default;
nullable items are tagged explicitly. This is what makes Kotlin's null
safety work against this Java API.

## License

[MIT](LICENSE).
