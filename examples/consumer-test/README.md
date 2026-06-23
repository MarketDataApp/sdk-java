# Examples

Small, self-contained programs that show how to use `marketdata-sdk-java`. Each one is meant to be
**read and copied** — pick the file that matches what you want to do, run it, look at the output.

They consume the SDK as a published artifact (via `mavenLocal`), exactly as your own project would,
so what you see is the real public API.

## Layout

```
src/main/java/com/marketdata/examples/
  resources/   one file per resource — the calls you'd write first, plus an "Advanced" file each
  common/      cross-cutting topics that apply to every resource (do these once, not per resource)
src/main/kotlin/com/marketdata/examples/
  Quickstart.kt   the same SDK, from Kotlin
```

### Resource examples (live API)

| Example | Shows |
|---|---|
| `UtilitiesExample` | API health, your account quota, request echo |
| `StocksExample` | candles, a quote, a multi-symbol batch quote |
| `OptionsExample` | symbol lookup, expirations, a filtered chain, a contract quote |
| `FundsExample` | mutual-fund NAV candles |
| `MarketsExample` | the exchange open/closed calendar |
| `…AdvancedExample` (one per resource) | universal params, date windows, column projection, CSV output, sealed chain filters |

### Cross-cutting examples (`common/`)

| Example | Shows | Needs |
|---|---|---|
| `SyncVsAsyncExample` | the sync call vs its async (`CompletableFuture`) variant, and a parallel fan-out | live API |
| `ConfigurationExample` | constructors, the config cascade, token redaction, fail-fast validation | offline |
| `ResponseFormatsExample` | the response wrapper: typed data, metadata, format predicates, raw body, `saveToFile` | live API |
| `ConcurrentRequestsExample` | firing many requests at once and the SDK's 50-request concurrency cap | mock server |
| `RetryAndBackoffExample` | automatic retry, exponential backoff, `Retry-After` | mock server |
| `ErrorHandlingExample` | the sealed `MarketDataException` hierarchy and how to branch on it | mock server |

The cross-cutting examples cover these behaviors **once**, using whichever resource is handy as the
vehicle — they're not repeated per resource.

## Running

```bash
# 1. Publish the SDK to your local Maven cache (run once, from the SDK root, two dirs up):
make publish

# 2. For the live examples, put your token where the SDK's cascade can find it:
echo "MARKETDATA_TOKEN=your-token-here" > examples/consumer-test/.env
```

Then run any example by its Gradle task (from this directory) or its `make` target (from the SDK
root):

```bash
./gradlew runStocks            # or:  make example-stocks
./gradlew runSyncVsAsync       # or:  make example-sync-async
./gradlew tasks --group examples   # list them all   (make example-list)
```

`UtilitiesExample`'s `status()` call, `SyncVsAsyncExample`, and `ResponseFormatsExample` use a public
endpoint and run **without** a token. The other resource examples need one (and options/funds data
needs the matching entitlements); without it they print a one-line hint instead of crashing.

## The mock server (for the three behavior examples)

`ConcurrentRequestsExample`, `RetryAndBackoffExample` and `ErrorHandlingExample` demonstrate things
you can't see against the live API — the concurrency cap, deterministic retry timing, each error
type on demand. They point the SDK at a local mock server whose responses are scripted up front.
Start it in another terminal first:

```bash
make mock-server          # from the SDK root
# or:  cd ../mock-server && ./run.sh
```

Without it those three examples fail fast with a clear "mock server not reachable" message. The mock
is a teaching aid (`com.marketdata.examples.util.MockServer`) — it is **not** part of the SDK and you
never need it in your own code.
