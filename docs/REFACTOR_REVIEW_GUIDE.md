# Refactor Review Guide — `clean-architecture-restart`

This guide walks a reviewer through the SDK foundation introduced on the `clean-architecture-restart` branch — a 54-commit, 92-file refactor. It is organized by **flow**, not by file. Each section names the parts of the code that participate in one slice of behavior, explains how they fit together, and calls out the non-obvious decisions.

If you've never read this codebase, start with §1 (topology) → §2 (sync request flow) → §10 (subtle corners). That covers the load-bearing shape in under an hour.

All file:line citations target `HEAD` on this branch. Line numbers drift; if a citation looks off, search for the symbol it names.

## Table of contents

- [Running it locally](#running-it-locally)
1. [SDK topology](#1-sdk-topology)
2. [Sync request flow end-to-end](#2-sync-request-flow-end-to-end)
3. [Construction flow](#3-construction-flow)
4. [Retry, `Retry-After`, and `StatusCache`](#4-retry-retry-after-and-statuscache)
5. [Rate-limit tracking + preflight](#5-rate-limit-tracking--preflight)
6. [Concurrency (`AsyncSemaphore`)](#6-concurrency-asyncsemaphore)
7. [`Response<T>` + JSON parsing](#7-responset--json-parsing)
8. [Sealed exception hierarchy](#8-sealed-exception-hierarchy)
9. [Configuration & logging](#9-configuration--logging)
10. [Subtle corners (issue-driven)](#10-subtle-corners-issue-driven)

---

## Running it locally

Most sections below end with a **Verify** note pointing at a runnable demo. The demos live under `examples/consumer-test/` and use the scriptable mock server under `examples/mock-server/`. The repo's `Makefile` wraps both — running anything from the SDK root looks like this:

```bash
make help                  # list all targets
make publish               # publish SDK to ~/.m2 — prereq for any demo

# Each mock-server demo needs the mock running in a separate terminal:
make mock-server           # terminal 2 (blocks; Ctrl+C to stop)
make demo-config           # terminal 3 — runs DemoAndConfigApp
make demo-retry            # ...etc

make demo-live             # hits api.marketdata.app (needs MARKETDATA_TOKEN, no mock needed)
make demos-all             # runs the five mock-server demos back-to-back
```

| Demo target | Section it exercises |
|---|---|
| `make demo-quickstart` | Idiomatic per-resource usage. Today `utilities` only; grows as `stocks`/`options`/`funds`/`markets` land. The "what does consumer code look like" demo. |
| `make demo-live` | End-to-end plumbing against the real API (§2 sync flow, §9 cascade, §8 rate-limit snapshot) |
| `make demo-config` | §3 construction, §9 configuration & logging |
| `make demo-exceptions` | §8 sealed exception hierarchy |
| `make demo-retry` | §4 retry + `Retry-After` + §5 preflight |
| `make demo-response` | §7 `Response<T>` surface |
| `make demo-concurrency` | §6 `AsyncSemaphore` cap |

Underneath, `make demo-X` is `cd examples/consumer-test && ./gradlew runX`, and `make publish` is `./gradlew publishToMavenLocal` — the Makefile is just convenience. If a target misbehaves, `make -n <target>` prints the underlying command without running it.

The mock server (`make mock-server`) is a FastAPI app on `127.0.0.1:8765` that the demo apps POST scripted responses to via `/_admin/script`, then make a real SDK call against the same host. That's how scenarios like "503 → 503 → 200 recovers in ~3 s" are made deterministic.

For unit tests:

```bash
make test                  # ./gradlew test
make build                 # full build: tests + Spotless + JaCoCo
./gradlew -PtestJdk=21 test  # JDK 21 unit tests (also works for 25)
```

The `-PtestJdk=N` toolchain flag has no Make wrapper — pass it to gradlew directly. CI runs the `{17, 21, 25}` matrix on push to `main`.

---

## 1. SDK topology

### 1.1 Package layout

All internal classes live in the root package `com.marketdata.sdk` per [ADR-007](adr/ADR-007-internal-api-encapsulation.md). The "internal" boundary is enforced by Java's package-private visibility rather than a subpackage rename — classes that consumers must not reach drop the `public` modifier. Two subpackages exist:

- `com.marketdata.sdk.exception` — public sealed hierarchy plus its `ErrorContext` record. Subpackage chosen so an `import com.marketdata.sdk.exception.*` brings in the full taxonomy.
- `com.marketdata.sdk.utilities` — public response models (`ApiStatus`, `ServiceStatus`, `User`, `RequestHeaders`). Each future resource will get a parallel subpackage.

The repo at HEAD has **53 files in `src/main`** (~3,700 LoC of production code) and **34 test classes**.

### 1.2 Public API surface

What a consumer can `import`:

```
com.marketdata.sdk.MarketDataClient
com.marketdata.sdk.UtilitiesResource             (returned from client.utilities())
com.marketdata.sdk.Response<T>
com.marketdata.sdk.RateLimitSnapshot

com.marketdata.sdk.exception.MarketDataException (sealed)
  ├── AuthenticationError
  ├── BadRequestError
  ├── NotFoundError
  ├── RateLimitError
  ├── ServerError
  ├── NetworkError
  └── ParseError
com.marketdata.sdk.exception.ErrorContext

com.marketdata.sdk.utilities.ApiStatus
com.marketdata.sdk.utilities.ServiceStatus
com.marketdata.sdk.utilities.User
com.marketdata.sdk.utilities.RequestHeaders
```

Everything else is package-private and therefore unreachable from consumer code. This includes — deliberately — `Configuration`, `EnvVars`, `Tokens`, `Version`, `RequestSpec`, `HttpTransport`, `HttpDispatcher`, `AsyncSemaphore`, `RetryPolicy`, `RetryExecutor`, `RetryAfterHeader`, `StatusCache`, `RateLimitHeaders`, `JsonResponseParser`, `ParallelArrays`, `Format`, `DateFormat`, `Mode`, `DemoMode`, `DotEnvLoader`, `MarketDataLogging`, `CanonicalLogFormatter`, `MarketDataDates`, `HttpStatusMapper`, `HttpResponseEnvelope`, `RequestHeadersDeserializer`, `UserDeserializer`.

`UtilitiesResource` is `public final` so the *type* can be named in `client.utilities()` return positions, but its constructor is package-private — consumers can hold a reference but cannot instantiate one.

### 1.3 Inventory by layer

#### Lifecycle & configuration

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `MarketDataClient` | public | 205 | Entry point. Holds `Configuration`, `HttpTransport`, `UtilitiesResource`. Drives the §4 cascade, §5 startup probe, and the §7 logger configuration. |
| `Configuration` | record (pkg) | 233 | Resolved configuration: `apiKey?`, `baseUrl`, `apiVersion`, `loggingLevel?`, `dateFormat?`. Owns the cascade (`resolve`), normalization, and validation. |
| `EnvVars` | pkg | ~30 | The only place that knows the `MARKETDATA_*` env-var names. `systemLookup()` restricts reads to the allowed set. |
| `DotEnvLoader` | pkg | 99 | `.env` reader with explicit `allowedKeys` filter and buffered `Warning`s. |
| `DemoMode` | pkg | 11 | One-line predicate: `apiKey == null`. |
| `Version` | pkg | 19 | `sdkVersion()` — JAR-manifest lookup with a build-time-injected fallback. |
| `Tokens` | pkg | 24 | `redact(token)` — `***…***` (≤8) / `***…***ABCD` (>8). |
| `MarketDataLogging` | pkg | 158 | Installs `CanonicalLogFormatter`. First-install-wins; consumer-pre-config detection. |
| `CanonicalLogFormatter` | pkg | 58 | JUL `Formatter` enforcing the §7 line shape. |
| `MarketDataDates` | pkg | 37 | Epoch-seconds → `ZonedDateTime` in America/New_York. |
| `Format`, `DateFormat`, `Mode` | pkg enums | 27–38 | Wire-format / date-format / mode enums with `wireValue()` accessors. |

#### Transport & dispatch

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `HttpTransport` | pkg | 411 | Orchestrates one request: build URL/request, preflight gate, dispatch, retry, route response. Owns `latestRateLimits`. |
| `HttpDispatcher` | pkg | 185 | Single-shot send under the `AsyncSemaphore`. Maps transport errors to `NetworkError`. |
| `HttpResponseEnvelope` | record (pkg) | 25 | Format-agnostic response carrier: `body[]`, `statusCode`, `requestId?`, `headers`, `url`. |
| `HttpStatusMapper` | pkg | 84 | Status code → `MarketDataException` subtype. |
| `RequestSpec` | record (pkg) | 134 | Declarative GET spec: `path`, `queryParams` (ordered), `format`, `versioned`. Builder. |

#### Retry, rate limiting, concurrency

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `RetryPolicy` | pkg | 191 | `shouldRetry(cause, attempt)` and `backoffDelay(cause, attempt)`. Static factories `defaults()` and `noRetry()`. |
| `RetryExecutor` | pkg | 151 | Generic retry-on-failure orchestrator for `Supplier<CompletableFuture<T>>`. |
| `RetryAfterHeader` | pkg | 64 | Parser for delta-seconds + RFC 1123 HTTP-date. |
| `StatusCache` | pkg | 167 | Stale-while-revalidate cache of `/status/`. Gates retries. |
| `RateLimitHeaders` | pkg | 53 | All-or-nothing parser for the four `x-api-ratelimit-*` headers. |
| `RateLimitSnapshot` | public record | ~10 | `limit`, `remaining`, `reset`, `consumed`. |
| `AsyncSemaphore` | pkg | 146 | Async-safe 50-permit limiter, FIFO waiter queue. |

#### Parsing & response

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `JsonResponseParser` | pkg | 89 | One `ObjectMapper` per client; resources self-register their `SimpleModule`. Pre-checks empty body. |
| `ParallelArrays` | pkg | 271 | `zip(...)` + `listDeserializer(...)` factory + strict `Row` accessors. |
| `RequestHeadersDeserializer` | pkg | 27 | Hand-written for the `{headers: {...}}` shape. |
| `UserDeserializer` | pkg | 54 | Hand-written for the `/user/` shape. |
| `Response<T>` | public | 150 | Typed `data()`, defensive `rawBody()`, format predicates, `isNoData()`, `saveToFile()`, redacted `toString()`. |

#### Resources

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `UtilitiesResource` | public (pkg ctor) | 144 | Sync + async pair for `/status/`, `/user/`, `/headers/` (all unversioned). Registers wire-format module on the parser. |
| `ApiStatus`, `ServiceStatus`, `User`, `RequestHeaders` | public records | <30 ea. | Response models. |

#### Exceptions

| Class | Visibility | Lines | Responsibility |
|---|---|---:|---|
| `MarketDataException` | public sealed | 96 | Base. Holds `ErrorContext`. `getRequestUrl` returns redacted URL. `getSupportInfo` formats the multi-line dump. |
| `ErrorContext` | public record | 17 | `requestId?`, `requestUrl`, `statusCode`, `timestamp`. |
| 7 permits | public final | 15–45 ea. | Subtypes. `RateLimitError` and `ServerError` carry an extra `retryAfter` Duration. |

### 1.4 Dependency arrows

A simplified view (not every static dependency — just the load-bearing ones):

```
                   MarketDataClient
                   │
       ┌───────────┼───────────────────┐
       ▼           ▼                   ▼
  Configuration  HttpTransport     UtilitiesResource ──── JsonResponseParser
       │           │   │                                       │
       │           │   ├─→ HttpDispatcher ─→ AsyncSemaphore    │
       │           │   ├─→ RetryExecutor ──→ RetryPolicy ─→ RetryAfterHeader
       │           │   ├─→ StatusCache ──────┐  ▲              │
       │           │   ├─→ HttpStatusMapper  │  │              │
       │           │   ├─→ RateLimitHeaders  │  │              │
       │           │   └─→ HttpResponseEnvelope            ParallelArrays
       │           │       (record)                            │
       │           ▼                                           ▼
       │       latestRateLimits ◀──── RateLimitSnapshot       Response<T>
       │
   DotEnvLoader, EnvVars, Tokens, MarketDataLogging, CanonicalLogFormatter
```

`MarketDataClient` is the only class that knows about all three of `Configuration`, `HttpTransport`, and `UtilitiesResource`. Everything else is one layer of abstraction below.

The `StatusCache → MarketDataClient → ... → HttpTransport → StatusCache` cycle (the cache's fetcher calls a `/status/` request through the transport) is resolved by a deferred reference — see §3 below.

---

## 2. Sync request flow end-to-end

This section traces a single line of consumer code:

```java
Response<ApiStatus> r = client.utilities().status();
```

through every class it touches, ending at the typed `Response<ApiStatus>`.

### 2.1 Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Consumer
    participant U as UtilitiesResource
    participant T as HttpTransport
    participant R as RetryExecutor
    participant D as HttpDispatcher
    participant S as AsyncSemaphore
    participant H as java.net.http.HttpClient
    participant SC as StatusCache
    participant P as JsonResponseParser
    participant PA as ParallelArrays

    C->>U: status()
    U->>U: statusAsync()<br/>build RequestSpec
    U->>T: executeAsync(spec)
    T->>T: build URI + HttpRequest
    T->>R: execute(supplier, shouldRetry)

    rect rgba(180,210,255,0.15)
        Note over R,SC: attempt N
        R->>T: supplier.get(attemptIdx, prevCause)
        T->>T: checkRateLimitPreflight(uri)
        alt remaining=0 & now<reset
            T-->>R: failedFuture(RateLimitError)
        else allow
            T->>D: dispatch(request)
            D->>S: acquire()
            S-->>D: permit (fast or slow)
            D->>H: sendAsync()
            H-->>D: HttpResponse<byte[]>
            D->>S: release()
            D-->>T: response
            T->>T: routeAndEnvelope()
            T->>T: parse rate-limit headers,<br/>update latestRateLimits
            alt 2xx or 404
                T-->>R: HttpResponseEnvelope
            else 4xx/5xx
                T-->>R: throw MarketDataException
            end
        end
        R->>R: shouldRetry?
        opt retriable + cache allows
            R->>SC: cache.check(uri)
            R-->>R: schedule next attempt (delayedExecutor)
        end
    end

    R-->>T: CompletableFuture<HttpResponseEnvelope>
    T-->>U: same future
    U->>P: parser.parse(env, ApiStatus.class)
    P->>PA: ParallelArrays.zip(root, fields, rowBuilder)
    PA-->>P: List<ServiceStatus>
    P-->>U: ApiStatus
    U->>U: Response.wrap(data, env, format)
    U-->>T: CompletableFuture<Response<ApiStatus>>
    T->>T: joinSync(future)<br/>unwrap CompletionException
    T-->>U: Response<ApiStatus>
    U-->>C: Response<ApiStatus>
```

### 2.2 Step-by-step walk

#### Entry — `UtilitiesResource`

`UtilitiesResource.status()` at `src/main/java/com/marketdata/sdk/UtilitiesResource.java:126` is a one-liner: `return transport.joinSync(statusAsync())`. It's a sync wrapper around the async surface, satisfying ADR-006's sync+async parity rule.

`statusAsync()` at line 120 builds a `RequestSpec`:

```java
RequestSpec spec = RequestSpec.get("status").unversioned().build();
return executeAndWrap(spec, ApiStatus.class);
```

`unversioned()` flips the `versioned` flag in the builder; `/status/` lives at the API root, not under `/v1/`. `executeAndWrap` (line 132) hands the spec to the transport and composes a `thenApply` that turns the raw envelope into a typed `Response<ApiStatus>`:

```java
return transport
    .executeAsync(spec)
    .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
```

#### Transport orchestration — `HttpTransport.executeAsync`

The orchestrator lives at `HttpTransport.java:153`. The interesting variant is the private 2-arg overload at line 167:

```java
private CompletableFuture<HttpResponseEnvelope> executeAsync(
    RequestSpec spec, RetryExecutor executor) {
  URI uri = buildUri(spec);
  HttpRequest request = buildHttpRequest(uri, spec.format());
  RetryPolicy policy = executor.policy();
  return executor.execute(
      (attemptIdx, previousCause) -> {
        if (!isServerHintedRetry(previousCause)) {
          RateLimitError preflight = checkRateLimitPreflight(uri);
          if (preflight != null) {
            return CompletableFuture.failedFuture(preflight);
          }
        }
        return dispatcher
            .dispatch(request)
            .thenApply(response -> routeAndEnvelope(response, uri));
      },
      (cause, attempt) -> policy.shouldRetry(cause, attempt) && cacheAllowsRetry(uri));
}
```

The lambda passed to `executor.execute` is what `RetryExecutor` invokes per attempt. The retry predicate is a composition of the policy's own decision with the `StatusCache`'s veto power (§9.5).

#### URL building — `buildUri`

`buildUri(spec)` at `HttpTransport.java:335` assembles `baseUrl + "/" + (versioned ? apiVersion + "/" : "") + path + "/" + (params ? "?…" : "")`. Two non-obvious pieces:

- Leading-slash defensiveness on `path` (line 340). `RequestSpec`'s Javadoc says paths have no leading slash, but a caller mistake would produce `baseUrl//v1//path` — strip defensively.
- Trailing-slash insertion (line 349). Every endpoint URL the API exposes ends in `/`; consumers who write `"status"` instead of `"status/"` shouldn't be surprised.

Query encoding uses a custom `encodeQueryComponent` (line 378) that replaces `+` with `%20` after `URLEncoder.encode`. `URLEncoder` emits `application/x-www-form-urlencoded` (`+` for spaces), which strict servers reject in query strings — the replacement is the canonical patch.

#### Preflight — `checkRateLimitPreflight`

`checkRateLimitPreflight(uri)` at `HttpTransport.java:221` is §10.3:

```java
RateLimitSnapshot snap = latestRateLimits.get();
if (snap == null || snap.remaining() > 0) return null;       // allow
Instant now = clock.instant();
if (!now.isBefore(snap.reset())) return null;                // reset has elapsed → allow
return new RateLimitError(...);                              // block
```

Two cases produce `null` (= allow): no snapshot yet, or the snapshot's reset time has passed. The second case is critical — without it, a single response carrying `remaining=0` would freeze the client forever (every subsequent request short-circuits, no response ever updates the snapshot). See §5 for the full reasoning.

The preflight is bypassed entirely when the previous attempt was a server-hinted retry (line 184). See §10.11.

#### Dispatch + permit — `HttpDispatcher.dispatch`

`dispatch(request)` at `HttpDispatcher.java:55`:

```java
CompletableFuture<Void> permit = permits.acquire();
CompletableFuture<HttpResponse<byte[]>> dispatched =
    permit.thenCompose(unused -> send(request));
dispatched.whenComplete((r, t) -> {
  if (t instanceof CancellationException) {
    permit.cancel(false);
  }
});
return dispatched;
```

`permits.acquire()` is the `AsyncSemaphore` from §6. It returns an *already-completed* future on the fast path (a permit was available) or a *pending* future on the slow path (the request waits in FIFO until a peer calls `release()`). Either way no thread is parked.

`send(request)` at line 75 calls `httpClient.sendAsync(...)` and registers `whenComplete((r, t) -> permits.release())` so the permit is released exactly once. The pre-send `try/catch` (line 80) handles `sendAsync` throwing synchronously (malformed request, OOM): release the permit explicitly because the `whenComplete` would never fire if the future never formed.

Transport-layer failures (anything that's not an `HttpResponse`) are mapped to `NetworkError` via the `handle(...)` block at line 105. The `unwrap` helper at line 182 peels one layer of `CompletionException`; deeper nesting is handled later in `RetryPolicy.hasIoExceptionInCauseChain` (§4 / §10.10).

#### Response routing — `routeAndEnvelope`

`routeAndEnvelope(response, uri)` at `HttpTransport.java:289` decides what the status code means.

First, rate-limit headers are parsed and the `latestRateLimits` `AtomicReference` is updated if (and only if) the four headers arrived together:

```java
RateLimitSnapshot parsed = RateLimitHeaders.parse(response.headers());
if (parsed != null) latestRateLimits.set(parsed);
```

The `if (parsed != null)` is load-bearing — see §5.

Then status routing (line 304):

```java
if ((status >= 200 && status < 300) || status == 404) {
  return new HttpResponseEnvelope(response.body(), status, requestId, response.headers(), uri);
}
```

**404 is a success.** The API uses HTTP 404 as the carrier for `{"s":"no_data"}` envelopes, which mean "we have nothing for that query" — successful, not error. The body is handed to the parser like any 2xx. The parser sees `s:"no_data"` and returns the empty container (§7). The `Response<T>` ends up with `isNoData() == true` (`Response.java:113`).

For 4xx/5xx (line 307+), a `Retry-After` is parsed up front (line 309) and attached to the resulting exception so that `RetryPolicy.backoffDelay` can honor it on the next attempt:

```java
Duration retryAfter = response.headers().firstValue("Retry-After")
    .flatMap(v -> RetryAfterHeader.parse(v, now))
    .orElse(null);
MarketDataException ex = HttpStatusMapper.map(status, context, retryAfter);
```

The exception is logged with `safeUri(uri)` — the query-stripped URL — and thrown. `RetryExecutor`'s `whenComplete` catches it.

If `HttpStatusMapper.map(...)` returns `null` (only possible for 2xx, already handled), a defensive `ServerError("Unmapped status …")` is thrown. Belt-and-suspenders: a future mapper edit can't silently swallow an unknown status.

#### Retry envelope — `RetryExecutor.execute`

`RetryExecutor.execute(AttemptSupplier, BiPredicate)` at `RetryExecutor.java:78`:

```java
CompletableFuture<T> result = new CompletableFuture<>();
AtomicReference<CompletableFuture<T>> currentAttempt = new AtomicReference<>();
result.whenComplete((r, t) -> {
  if (t instanceof CancellationException) {
    CompletableFuture<T> inFlight = currentAttempt.get();
    if (inFlight != null && !inFlight.isDone()) inFlight.cancel(false);
  }
});
attempt(supplier, shouldRetry, 0, null, result, currentAttempt);
return result;
```

One cancellation handler installed once on the *outer* `result`. The `currentAttempt` reference tracks whichever attempt is in flight; cancelling `result` cancels the live one. Previous attempts are already done by the time the next one overwrites the reference, so this avoids accumulating one handler per attempt.

`attempt(...)` at line 99 is the recursive worker. The body is intentionally small:

```java
if (result.isDone()) return;                  // caller cancelled or already failed
CompletableFuture<T> dispatched = supplier.get(attemptIdx, previousCause);
currentAttempt.set(dispatched);
if (result.isCancelled() && !dispatched.isDone()) {
  dispatched.cancel(false);
  return;
}
dispatched.whenComplete((value, error) -> {
  if (result.isDone()) return;
  if (error == null) { result.complete(value); return; }
  Throwable cause = unwrap(error);
  if (shouldRetry.test(cause, attemptIdx)) {
    long delayMs = policy.backoffDelay(cause, attemptIdx).toMillis();
    CompletableFuture.delayedExecutor(delayMs, MILLISECONDS)
        .execute(() -> attempt(supplier, shouldRetry, attemptIdx + 1, cause, result, currentAttempt));
  } else {
    result.completeExceptionally(cause);
  }
});
```

`delayedExecutor` is the key. No scheduled thread pool to manage — `ForkJoinPool.commonPool` runs the next attempt after the delay elapses.

The `currentAttempt.set(dispatched)` line is followed by an *immediate* re-check of cancellation. There's a TOCTOU window between the outer `isDone()` check and the `set()` call; the cancellation handler observes `currentAttempt`, so if cancellation fires in that window, it sees the previous (already-done) attempt and doesn't cancel the new one. The re-check closes the race.

#### Parser — `JsonResponseParser.parse`

`parse(env, type)` at `JsonResponseParser.java:55` is two paths:

1. Empty body (line 60) — fast-path `ParseError` with a précis message before Jackson is invoked. See §10.9.
2. Otherwise — `mapper.readValue(env.body(), type)`. Jackson reads the body and invokes the registered deserializer for `type`. For `ApiStatus.class`, that's the deserializer produced by `ParallelArrays.listDeserializer(...)` in `UtilitiesResource.wireFormatModule()` (`UtilitiesResource.java:51`).

If Jackson throws `IOException` (malformed JSON, type mismatch, etc.), the parser wraps it in `ParseError` (line 80). The message uses `safeUri(env.url())` — query-stripped — so a routine `logger.error(parseError.getMessage())` doesn't leak `?token=`.

#### Parallel-arrays decode — `ParallelArrays.zip`

`zip(p, root, fields, rowBuilder)` at `ParallelArrays.java:69`:

```java
String envelopeStatus = root.path("s").asText("");
if ("error".equals(envelopeStatus)) {
  throw new JsonMappingException(p, "API responded with error: " + errmsg);
}
if ("no_data".equals(envelopeStatus)) {
  return List.of();
}
// validate columns: every requested field must be a present, equal-length array
// then build rows via rowBuilder(new IndexedRow(arrays, i))
```

`s:"error"` short-circuits to a `JsonMappingException` carrying the server-supplied `errmsg`. The parent parser catches the `IOException` and re-wraps as `ParseError`. The consumer ends up with `ParseError.getMessage()` containing the server's `errmsg` — actionable.

`s:"no_data"` returns the empty list and the wrapper record's compact constructor copies it: `ApiStatus(List.of())`. The consumer's `Response.data().services()` is empty; `Response.isNoData()` is `true` (status was 404).

Column validation enforces presence and equal length (lines 83–102). Any deviation throws `JsonMappingException` → `ParseError`. The `Row` accessors are strict by default — see §10.4.

#### Response wrap — `Response.wrap`

`Response.wrap(data, envelope, format)` at `Response.java:54` builds the immutable `Response<T>`:

```java
return new Response<>(
    data, envelope.body(), format, envelope.statusCode(), envelope.requestId(), envelope.url());
```

The constructor at line 35 clones `rawBody` defensively. The accessor `rawBody()` clones again on every call (line 67) — see §10.2 for why that's not paranoid.

#### Sync unwrap — `HttpTransport.joinSync`

Back in `UtilitiesResource.status()`, `transport.joinSync(future)` at `HttpTransport.java:264`:

```java
try {
  return future.join();
} catch (CompletionException e) {
  throw asRuntime(e.getCause(), clock);
} catch (CancellationException e) {
  throw asRuntime(e, clock);
}
```

`asRuntime(...)` at line 399 has three branches:
- `MarketDataException` → return verbatim.
- `RuntimeException` → return verbatim.
- Anything else → wrap as `NetworkError` with a `forNoResponse` context.

The first branch is the one that fires in practice — the SDK always wraps failures as `MarketDataException` before they reach here. The other two are defensive guardrails so a future bug that lets some other type through doesn't surface as a confusing `CompletionException`.

### 2.3 Branches a reviewer should think about

| Branch | Path | Outcome |
|---|---|---|
| 200 OK with parseable body | `routeAndEnvelope → JsonResponseParser → ParallelArrays → Response.wrap` | Typed `Response<T>` with `isNoData() == false`. |
| 200 OK with `s:"error"` body | `JsonResponseParser` → `ParallelArrays` throws | `ParseError` with server's `errmsg` in the message. |
| 200 OK with truncated parallel arrays | `ParallelArrays` length-check fails | `ParseError`. |
| 200 OK with empty body | `JsonResponseParser` empty-body pre-check | `ParseError` "Empty response body…". |
| 404 + `s:"no_data"` | Route to success envelope, parser zips to `List.of()` | `Response<T>` with `isNoData() == true`. |
| 401 | `HttpStatusMapper.map(401, ...)` → `AuthenticationError` → `joinSync` unwraps | `AuthenticationError` thrown sync. |
| 503 (single shot) | `routeAndEnvelope` throws `ServerError(503, ...)`; retry retries | If recovers within budget: `Response<T>`. Otherwise `ServerError`. |
| ConnectException buried in `ExecutionException` | `HttpDispatcher` wraps in `NetworkError`; `RetryPolicy.hasIoExceptionInCauseChain` walks chain | Retried (§10.10). |
| Caller cancels the returned `CompletableFuture<T>` | `RetryExecutor.whenComplete` propagates cancel to `currentAttempt` | `CancellationException`. |

### 2.4 Verify locally

```bash
make publish
make demo-live          # hits api.marketdata.app — needs MARKETDATA_TOKEN
```

`LiveSmokeApp` prints `client.toString` (token redacted), runs `/status/` sync + async to prove ADR-006 parity, fires three async calls in parallel and reports the elapsed wall-time (should be ≈ the slowest single call, not the sum), and dumps the final rate-limit snapshot. Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/LiveSmokeApp.java`.

---

## 3. Construction flow

This section walks through `new MarketDataClient(...)`. The constructor is dense — every line earns its place.

### 3.1 Flowchart

```mermaid
flowchart TD
    A[ctor entry] --> B[DotEnvLoader.load with ALLOWED_KEYS]
    B --> C{Configuration.resolve<br/>cascade + validate}
    C -- throws IAE --> X[attach warnings as suppressed<br/>+ rethrow]
    C -- OK --> D[Configuration record]
    D --> E[MarketDataLogging.configure]
    E --> F[replay buffered warnings<br/>through logger]
    F --> G[log INFO 'initialized'<br/>with token redacted]
    G --> H[AtomicReference&lt;StatusCache&gt;<br/>cacheRef]
    H --> I[HttpTransport.withDefaults<br/>uses cacheRef::get]
    I --> J{partial-construction guard}
    J -- try --> K[new JsonResponseParser]
    K --> L[new UtilitiesResource<br/>registers wire-format module]
    L --> M[new StatusCache<br/>cacheRef.set]
    J -- throws --> Y[transport.close + addSuppressed<br/>+ rethrow]
    M --> N{validateOnStartup?}
    N -- no --> Z[ctor returns]
    N -- yes --> O{DemoMode.isDemo}
    O -- yes --> P[log skip] --> Z
    O -- no --> Q[utilities.validateAuth<br/>noRetry policy]
    Q -- 200 --> Z
    Q -- 401 --> R[AuthenticationError<br/>close + addSuppressed<br/>rethrow]
    Q -- other --> R
```

### 3.2 Step-by-step walk

#### Public 4-arg constructor

`MarketDataClient(apiKey, baseUrl, apiVersion, validateOnStartup)` at `MarketDataClient.java:28` just delegates to the package-private 6-arg constructor with the production seams:

```java
this(apiKey, baseUrl, apiVersion, validateOnStartup,
     EnvVars.systemLookup(), Configuration.DEFAULT_DOTENV_PATH);
```

The two extra params (`env` lookup and `.env` path) are the seams that let tests drive the cascade hermetically — no `System.getenv` reads, no real filesystem.

#### Buffer-then-replay warnings

`MarketDataClient.java:54`:

```java
List<DotEnvLoader.Warning> pendingWarnings = new ArrayList<>();
try {
  this.config = Configuration.resolve(apiKey, baseUrl, apiVersion, env, dotEnvPath, pendingWarnings::add);
} catch (RuntimeException e) {
  attachWarningsAsSuppressed(e, pendingWarnings);
  throw e;
}
```

`DotEnvLoader` runs *inside* `Configuration.resolve` — i.e. before `MarketDataLogging.configure(...)` has had a chance to install the SDK's logger. If `DotEnvLoader` logged its warnings directly, they'd land on an unconfigured JUL logger with the wrong shape, possibly invisible. Buffering them and replaying after `configure` is the only way to get §7-shaped warnings on every rung of the cascade.

Issue #25 wired the failure path: if `Configuration.resolve` itself throws (typically `IllegalArgumentException` on a bad `baseUrl`/`apiVersion`/`apiKey`), the warnings are attached as suppressed exceptions on the primary cause. Without this, the consumer would lose the `.env` warning that explained *why* the config went wrong.

#### Configuration cascade

`Configuration.resolve(...)` at `Configuration.java:40` does five things:

1. `DotEnvLoader.load(dotEnvPath, warnings, EnvVars.ALLOWED_KEYS)` — read `.env` if present, filtering to allowed keys only.
2. `pickFirst(explicit, env, dotEnv)` for nullable values (`apiKey`, `loggingLevel`, `dateFormat`). `pickFirstOrDefault` for non-nullable ones (`baseUrl`, `apiVersion`).
3. `normalizeBaseUrl` — strip trailing slashes.
4. `normalizeApiVersion` — strip leading/trailing slashes.
5. `validateBaseUrl`, `validateApiVersion`, `validateApiKey`.

`validateBaseUrl` (line 125) rejects empty strings, non-parseable URIs, non-http(s) schemes, missing hosts, and the presence of query/fragment/user-info. The `user-info` check is included because someone pasting `https://user:pass@api.marketdata.app` into `baseUrl` is almost always confused about where credentials go.

`validateApiVersion` (line 207) checks against the `[A-Za-z0-9._-]+` regex — a single URL-safe path segment. This rejects `"v1/extra"`, `"%2Fv1"`, spaces, etc.

`validateApiKey` (line 189) — issue #23 — checks every character is printable ASCII (`[0x20, 0x7E]`). CR/LF would be rejected later by `HttpRequest.Builder#header` with a generic IAE; rejecting here gives a clear constructor-time message that names the offset. NUL and high-bit bytes are also rejected because they're almost always a copy-paste mishap. Demo mode (`apiKey == null`) is exempted.

#### Logger configuration

`MarketDataClient.java:71`:

```java
MarketDataLogging.configure(config.loggingLevel());
for (DotEnvLoader.Warning w : pendingWarnings) {
  LOGGER.log(w.level(), w.message(), w.cause());
}
LOGGER.info(() -> "MarketDataClient initialized: baseUrl=" + ... + token=Tokens.redact(...));
```

After `configure` returns, every subsequent log line (including the replay of buffered warnings) emits in the canonical §7 format. The `INFO` line at the end records the resolved configuration — including the token, which is run through `Tokens.redact` (§9.3).

See §9.2 for `MarketDataLogging.configure`'s internals (consumer-pre-config detection, etc.).

#### Deferred StatusCache reference

`MarketDataClient.java:90`:

```java
AtomicReference<StatusCache> cacheRef = new AtomicReference<>();
this.transport = HttpTransport.withDefaults(
    config.baseUrl(), config.apiVersion(),
    "marketdata-sdk-java/" + Version.sdkVersion(),
    config.apiKey(),
    cacheRef::get);
```

This is the **chicken-and-egg solution** that §10.12 covers in detail. Briefly: the transport needs the cache (to gate retries) and the cache needs the transport (to fetch `/status/`). The `AtomicReference` lets us build the transport first with a `Supplier<StatusCache>` that returns `null` until the cache is constructed. The transport handles `null` gracefully — `cacheAllowsRetry` short-circuits to `true` when the supplier returns `null` (`HttpTransport.java:240`).

#### Partial-construction guard

`MarketDataClient.java:105`:

```java
try {
  JsonResponseParser parser = new JsonResponseParser();
  this.utilities = new UtilitiesResource(transport, parser);
  cacheRef.set(new StatusCache(
      () -> utilities.statusAsync().thenApply(Response::data), Clock.systemUTC()));
} catch (Throwable t) {
  try { transport.close(); } catch (Throwable closeFailure) { t.addSuppressed(closeFailure); }
  throw t;
}
```

From the line where `this.transport = ...` succeeds, the transport is a live `AutoCloseable` holding the shared `HttpClient` and the 50-permit `AsyncSemaphore`. If any subsequent line in the constructor throws, the caller never receives a `MarketDataClient` reference, their try-with-resources never fires, and the transport leaks until GC. The explicit close-and-rethrow makes that impossible.

Today, no line below `this.transport = ...` is expected to throw — `JsonResponseParser` is trivial, `UtilitiesResource`'s constructor just registers a module, `StatusCache`'s constructor just stores references. But the guard exists because a future refactor of any of those constructors could break the invariant silently.

`UtilitiesResource`'s constructor at `UtilitiesResource.java:27` calls `parser.registerModule(wireFormatModule())`, which is where Jackson learns how to decode `ApiStatus`, `User`, `RequestHeaders`. This must happen before any `parse(...)` call — satisfied because resources are constructed before any HTTP request is made.

#### Startup validation

`MarketDataClient.java:120`:

```java
if (validateOnStartup) {
  runStartupValidation();
}
```

`runStartupValidation()` at line 160:

```java
if (DemoMode.isDemo(config)) {
  LOGGER.info(() -> "validateOnStartup skipped: demo mode is active (no token configured).");
  return;
}
try {
  utilities.validateAuth();
} catch (Throwable t) {
  try { close(); } catch (Throwable closeFailure) { t.addSuppressed(closeFailure); }
  throw t;
}
```

`utilities.validateAuth()` at `UtilitiesResource.java:110`:

```java
void validateAuth() {
  transport.joinSync(
      executeAndWrap(RequestSpec.get("user").build(), RetryPolicy.noRetry(), User.class));
}
```

Three deliberate choices:

1. **`RetryPolicy.noRetry()`** — a single attempt. A slow/down API surfaces here within the 99 s request timeout instead of burning the default budget (~6.75 min worst case). Consumers who need a tighter ceiling pass `validateOnStartup = false` and probe themselves.
2. **Result discarded** — only the throw shape matters. 401 → `AuthenticationError`, network failures → `NetworkError`, etc.
3. **Package-private and intent-named** — not a public `/user/` endpoint (that's `utilities.user()` with a custom retry path). Sharing the codepath but not the name keeps "auth probe" and "fetch user data" semantically distinct in the source.

The constructor-level catch (`MarketDataClient.java:169`) closes the transport on any failure so a partially-constructed client doesn't leak. Same suppressed-exception pattern as the partial-construction guard above.

### 3.3 Cases to call out

| Scenario | Outcome |
|---|---|
| All cascade rungs empty, `apiKey = null` | Demo mode. `validateOnStartup` skipped. Constructor returns. |
| `validateOnStartup = true` + 200 | Constructor returns OK. `latestRateLimits` is populated from the `/user/` response headers as a side effect. |
| `validateOnStartup = true` + 401 | `AuthenticationError` thrown from constructor. Transport closed. |
| `validateOnStartup = true` + network failure | `NetworkError` thrown from constructor. Transport closed. (After the no-retry single attempt — no backoff burnt.) |
| `apiKey` contains CRLF | `IllegalArgumentException` at construct time, no transport allocated yet. `.env` warnings attached as suppressed. |
| `baseUrl = "not-a-url"` | `IllegalArgumentException` at construct time. |
| `.env` is unreadable | Warning collected, attached as suppressed if any later step throws; otherwise replayed through the logger as a WARNING. |

### 3.4 Verify locally

```bash
make publish
make mock-server        # terminal 2
make demo-config        # terminal 3
```

`DemoAndConfigApp` walks each construction-time scenario in order: demo mode (when the cascade resolves no token), token redaction at the 8-char hinge (short and long), explicit-wins cascade, IAE on malformed `baseUrl`, CRLF rejection on `apiKey`, `validateOnStartup` success against a 200, and `validateOnStartup` failing against a scripted 401. Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/DemoAndConfigApp.java`.

---

## 4. Retry, `Retry-After`, and `StatusCache`

This section covers the §9 retry contract end-to-end.

### 4.1 Decision flowchart

```mermaid
flowchart TD
    A[attempt N fails] --> B{cause is<br/>MarketDataException?}
    B -- no --> NO[no retry]
    B -- yes --> C{type?}

    C -- NetworkError --> D{IOException in<br/>cause chain<br/>depth ≤ 16?}
    D -- no --> NO
    D -- yes --> R1[retry candidate]

    C -- ServerError --> E{statusCode in<br/>501-599?}
    E -- no --> NO
    E -- yes --> R1

    C -- RateLimitError<br/>BadRequestError<br/>AuthenticationError<br/>NotFoundError<br/>ParseError --> NO

    R1 --> F{attempt+1 < maxAttempts?}
    F -- no --> NO
    F -- yes --> G{StatusCache.check<br/>== ALLOW?}
    G -- BLOCK --> NO
    G -- ALLOW --> H[backoffDelay]

    H --> I{cause is ServerError<br/>with Retry-After?}
    I -- no --> J[exponential:<br/>initial × 2^attempt<br/>capped at maxBackoff]
    I -- yes --> K{retryAfter ≤<br/>MAX_RETRY_AFTER<br/>10 min?}
    K -- yes --> L[use retryAfter]
    K -- no --> M[log warning,<br/>use exponential]
    J --> Z[schedule attempt N+1<br/>via delayedExecutor]
    L --> Z
    M --> Z
```

### 4.2 `RetryPolicy.shouldRetry`

`RetryPolicy.java:79`:

```java
boolean shouldRetry(Throwable cause, int attempt) {
  if (attempt + 1 >= maxAttempts) return false;
  return isRetriable(cause);
}
```

`attempt` is zero-indexed: `attempt == 0` means the original call just failed and we're considering the *first* retry. The default `maxAttempts = 4` means up to 3 retries (attempts at indices 0, 1, 2 schedule retries; attempt at index 3 surfaces).

`isRetriable(cause)` at line 136 has three branches:

1. **Not a `MarketDataException`** → false. Conservative: an unknown failure type doesn't get retried.
2. **`NetworkError`** → `hasIoExceptionInCauseChain(net.getCause())`. See §10.10 — the walk is critical under HTTP/2.
3. **`ServerError`** → `status in [501, 599]`. 500 is explicitly excluded (deterministic server bug — retrying just hits the same crash). The `0` sentinel from `ErrorContext.forNoResponse` falls outside the range, so a `ServerError` without an HTTP code (impossible today but defensible) is correctly excluded.

`RateLimitError`, `AuthenticationError`, `BadRequestError`, `NotFoundError`, `ParseError` all return `false` — §9 says never retry 4xx, and `ParseError` is deterministic.

### 4.3 `RetryPolicy.backoffDelay`

`RetryPolicy.java:91`:

```java
Duration backoffDelay(Throwable cause, int attempt) {
  if (cause instanceof ServerError server) {
    Duration override = server.getRetryAfter().orElse(null);
    if (override != null) {
      if (override.compareTo(MAX_RETRY_AFTER) > 0) {
        LOGGER.warning(() -> "Server-supplied Retry-After of ... exceeds cap ... ignoring");
      } else {
        return override;
      }
    }
  }
  return backoffDelay(attempt);
}
```

The override-or-exponential decision tree. The `MAX_RETRY_AFTER = 10 minutes` cap (issue #21, §10.6) is what prevents pathological values from freezing the next retry for hours.

`backoffDelay(int attempt)` at line 119 is the pure exponential calculation with two saturation guards (line 128 and the rearranged inequality at line 132) so the math doesn't silently wrap on large attempt indices. Today `maxAttempts = 4` makes this defensive — but a consumer test that constructs a `RetryPolicy(100, ...)` shouldn't get `Long`-overflow surprises.

### 4.4 `RetryAfterHeader.parse`

`RetryAfterHeader.java:33`:

```java
static Optional<Duration> parse(String value, Instant now) {
  String trimmed = value.trim();
  if (trimmed.isEmpty()) return Optional.empty();
  Optional<Duration> asSeconds = parseSeconds(trimmed);
  if (asSeconds.isPresent()) return asSeconds;
  return parseHttpDate(trimmed, now);
}
```

Order matters: `parseSeconds` first, then `parseHttpDate`. RFC 1123 dates contain non-digits so `Long.parseLong` fails fast and falls through.

Both parsers `Math.max(0L, ...)` the result. Negative deltas and past dates clamp to `Duration.ZERO` — "retry now". Malformed values produce `Optional.empty()`, which the transport at `HttpTransport.java:313` flows through `.flatMap(...).orElse(null)` so the `ServerError` ends up with `retryAfter = null` and `backoffDelay` falls back to exponential.

This file is intentionally cap-free. The 10-min cap lives at the policy layer (`RetryPolicy`); the parser stays a pure RFC 7231 implementation that any other code in the SDK could reuse without inheriting policy decisions.

### 4.5 `RetryExecutor`

Covered structurally in §2.2 ("Retry envelope"). Two invariants worth restating:

1. **One cancellation handler.** The handler is installed once on the outer `result`. Each attempt is tracked in the `currentAttempt` `AtomicReference`; cancelling `result` cancels the live one. No handler accumulation across retries.
2. **Re-check after `currentAttempt.set`.** A race exists between the outer `isDone()` and the `set(...)`: if cancellation fires inside the window, the cancellation handler sees the *previous* (done) attempt and doesn't cancel the *new* one. The immediate re-check (`RetryExecutor.java:119`) closes the race.

The supplier is invoked with `(attemptIdx, previousCause)`. `previousCause` is what makes the server-hinted-retry bypass possible — see §10.11.

### 4.6 `StatusCache`

`StatusCache.check(uri)` at `StatusCache.java:61`:

```java
Snapshot snap = snapshot.get();
Instant now = clock.instant();
boolean refreshNeeded = snap == null ||
    Duration.between(snap.fetchedAt, now).compareTo(REFRESH_THRESHOLD) >= 0;
if (refreshNeeded) {
  triggerRefresh();
  snap = snapshot.get();  // issue #19 — see §10.7
}
boolean usable = snap != null && Duration.between(snap.fetchedAt, now).compareTo(EXPIRY) < 0;
if (!usable) return Decision.ALLOW;
String status = lookupService(snap, uri);
return "offline".equals(status) ? Decision.BLOCK : Decision.ALLOW;
```

The TTL is stale-while-revalidate:

- `age < 270s` (REFRESH_THRESHOLD) — serve cached, no refresh.
- `270s ≤ age < 300s` (EXPIRY) — serve cached, fire async refresh.
- `age ≥ 300s` or no cache — treat as "unknown" → ALLOW (§9.5 says unknown allows retries) and fire async refresh.

`triggerRefresh()` at line 90 is gated by an `AtomicBoolean` so concurrent retries on different services don't fire N refreshes. The fetcher is consulted via a `Supplier<CompletableFuture<ApiStatus>>` — see §10.12 for why this indirection exists.

If the refresh future fails, the previous snapshot survives (§9.5 says cache persists across failed refreshes). The failure is logged at WARNING level so operators can detect a `/status/` outage instead of wondering why the SDK keeps blocking retries.

`lookupService` at line 130 is the longest-prefix-match (§10.5).

### 4.7 The full retry composition

In `HttpTransport.executeAsync` (line 197) the retry predicate is:

```java
(cause, attempt) -> policy.shouldRetry(cause, attempt) && cacheAllowsRetry(uri)
```

`policy.shouldRetry` is the §9.3 decision. `cacheAllowsRetry(uri)` is the §9.5 veto (with the self-bypass for `/status/`, §10.7). Both must say "yes" for a retry to proceed.

### 4.8 Cases to call out

| Scenario | Wall-time | Outcome |
|---|---|---|
| 503 → 503 → 200 | ~3 s (1 s + 2 s backoff) | `Response<T>`. |
| 503 → 503 → 503 → 503 | ~7 s (1 + 2 + 4) | `ServerError`. |
| 503 with `Retry-After: 5` → 200 | ~5 s | `Response<T>`. Server hint honored. |
| 503 with `Retry-After: 86400` → 200 | ~1 s | `Response<T>`. Cap engaged, fell back to exponential. The 86400 is still visible on the `ServerError.getRetryAfter()` for consumer-side inspection of the first-attempt failure. |
| 500 (single shot) | ~0 s | `ServerError`. Not retriable. |
| `ConnectException` buried in `ExecutionException → CompletionException → ConnectException` | ~7 s after 3 retries | `NetworkError`. Retried thanks to the cause-chain walk (§10.10). |
| `ParseError` from a 200 with malformed body | ~0 s | `ParseError`. Never retried. |
| `/status/` reports service offline → 503 on the affected URI | ~0 s | `ServerError`. Retry vetoed by `StatusCache`. |
| Caller cancels `CompletableFuture` mid-backoff | — | `CancellationException`. The scheduled next attempt is never run because `attempt()` checks `result.isDone()` first. |

### 4.9 Verify locally

```bash
make publish
make mock-server        # terminal 2
make demo-retry         # terminal 3
```

`RetryBehaviorApp` scripts: `503 → 503 → 200` (expect ~3 s recovery), `503 + Retry-After: 3 → 200` (~3 s, exponential bypassed), `503 + Retry-After: <RFC-1123 date>` (HTTP-date honored), `503 + Retry-After: 86400` (cap engaged, falls back to ~1 s exponential), and the §10.3 preflight test (snapshot reports `remaining=0` → second call fails in 0 ms with 0 server-side requests). The wall-clock printed for each scenario is the proof. Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/RetryBehaviorApp.java`.

---

## 5. Rate-limit tracking + preflight

### 5.1 The data path

The server sets four headers on every successful response:

```
x-api-ratelimit-limit:     <int>     # plan-wide cap
x-api-ratelimit-remaining: <int>     # remaining in window
x-api-ratelimit-reset:     <epoch_s> # when remaining resets
x-api-ratelimit-consumed:  <int>     # consumed in window
```

`RateLimitHeaders.parse(headers)` at `RateLimitHeaders.java:28` reads all four. The reader is **all-or-nothing**: if any header is missing or unparseable, `parse` returns `null`. The reasoning lives in the file's class Javadoc:

> Returns `null` when the four headers do not arrive together. … A partial delivery is a server-side rate-limit-tracking outage, not legitimate data. Returning `null` … preserves the caller's last-known-good snapshot instead of clobbering it with phantom zeros — those would otherwise trip `checkRateLimitPreflight` into blocking subsequent requests with a fake `remaining=0`.

`HttpTransport.routeAndEnvelope` (line 293) updates the snapshot only when the parser returns non-null:

```java
RateLimitSnapshot parsed = RateLimitHeaders.parse(response.headers());
if (parsed != null) latestRateLimits.set(parsed);
```

The reverse path — `client.getRateLimits()` at `MarketDataClient.java:184` — just returns `transport.getLatestRateLimits()`, which is the `AtomicReference.get()`.

### 5.2 The preflight gate

`HttpTransport.checkRateLimitPreflight(uri)` at line 221:

```java
RateLimitSnapshot snap = latestRateLimits.get();
if (snap == null || snap.remaining() > 0) return null;        // allow
Instant now = clock.instant();
if (!now.isBefore(snap.reset())) return null;                 // reset elapsed → allow
ErrorContext context = ErrorContext.forNoResponse(uri.toString(), now);
return new RateLimitError(
    "Rate limit exhausted: 0 requests remaining (resets at " + snap.reset() + ")", context);
```

Three branches:

1. **No snapshot yet** (cold start, or every response so far lacked rate-limit headers) → allow. The next response will populate it.
2. **`remaining > 0`** → allow.
3. **`remaining == 0` and `now < reset`** → block with `RateLimitError`. The exception's `ErrorContext` uses `forNoResponse` because no HTTP round-trip happened; `statusCode == 0`, `requestId == null`.

The "reset elapsed → allow" branch on line 227 is what prevents the **stuck-forever** failure mode: without it, a single response carrying `remaining=0` would short-circuit every subsequent request, no request would ever reach the wire, and the snapshot would never refresh. By letting the request through once `now >= reset`, the server's response refreshes the snapshot. If the server hasn't actually replenished credits yet it will reject with 429, which costs one round-trip — strictly better than locking the client out indefinitely.

`RateLimitError` is non-retriable per `RetryPolicy.isRetriable`. The retry executor sees the failed-future returned by the supplier, the policy says no, and the consumer gets the error directly.

### 5.3 The server-hinted-retry bypass

`HttpTransport.executeAsync` (line 184):

```java
if (!isServerHintedRetry(previousCause)) {
  RateLimitError preflight = checkRateLimitPreflight(uri);
  if (preflight != null) return CompletableFuture.failedFuture(preflight);
}
```

`isServerHintedRetry(previousCause)` at line 206:

```java
return previousCause instanceof ServerError server && server.getRetryAfter().isPresent();
```

When a retry was scheduled because the previous attempt returned `503 + Retry-After: 5`, the server has just told us "come back at `now + 5s`". That directive is more authoritative than our snapshot for this specific retry. Without the bypass, a snapshot reporting `remaining=0` with a far-future `reset` would veto the server-orchestrated backoff — the retry would never reach the wire. See §10.11.

### 5.4 Cases to call out

| State | Behavior |
|---|---|
| First-ever call from a new `MarketDataClient` | Snapshot is `null`. Preflight allows. Response (if successful and carrying headers) populates the snapshot. |
| Snapshot says `remaining = 10, reset = now+1h` | Allow. |
| Snapshot says `remaining = 0, reset = now+1h` | Block with `RateLimitError` instantly. Server sees zero additional requests until the second condition fails. |
| Snapshot says `remaining = 0, reset = now-1s` | Allow. The response refreshes the snapshot. |
| Retry of a 503 with `Retry-After: 5`, snapshot says `remaining = 0, reset = now+1h` | Allow (bypass). Server's directive prevails. |
| Response without rate-limit headers (e.g., 500 from internal server error) | Snapshot is not updated. Last-known-good survives. |

### 5.5 Verify locally

Same demo as §4 — the preflight check is the last scenario in `RetryBehaviorApp`:

```bash
make publish
make mock-server        # terminal 2
make demo-retry         # terminal 3 — scroll to "§10.3 preflight" output
```

The demo scripts one 200 carrying `x-api-ratelimit-remaining: 0` and a far-future `reset`, then issues a second call. Expectation: the second call fails with `RateLimitError` in ~0 ms and the server logs zero additional requests (visible via `/_admin/stats`). Source: the `preflightBlocksWhenSnapshotExhausted` method.

---

## 6. Concurrency (`AsyncSemaphore`)

### 6.1 Why not `j.u.c.Semaphore`

`java.util.concurrent.Semaphore.acquire()` is blocking. The caller's thread parks until a permit is available. That's incompatible with ADR-006's async-first design: `executeAsync` must return a `CompletableFuture` immediately, even when the pool is exhausted, so a consumer's `client.utilities().statusAsync().thenApply(...)` chain doesn't accidentally pin a thread inside the SDK.

`AsyncSemaphore.acquire()` returns a `CompletableFuture<Void>`. Fast path: a permit is available → already-completed future. Slow path: a permit isn't available → pending future enqueued FIFO; it completes when some peer calls `release()`. Either way, no thread parks.

### 6.2 Invariants

From the class Javadoc at `AsyncSemaphore.java:16`:

1. **Every permit is accounted for exactly once.** A permit is either in `available` (free counter), held by an in-flight caller (will be `release()`d), or pending in the waiter queue (will be released by completing the waiter's future). Never two of those at once.
2. **`CompletableFuture.complete(...)` always runs outside the lock.** Completing a future runs the caller's attached callbacks synchronously on the releasing thread. Doing that with a lock held is a deadlock waiting to happen.

### 6.3 Acquire

`AsyncSemaphore.acquire()` at line 54:

```java
synchronized (lock) {
  if (closed) return CompletableFuture.failedFuture(closedException());
  if (available > 0) {
    available--;
    return CompletableFuture.completedFuture(null);
  }
  CompletableFuture<Void> waiter = new CompletableFuture<>();
  waiters.addLast(waiter);
  return waiter;
}
```

Notice the lock is held only for the counter decrement / enqueue. The completed future and the new pending future are both constructed inside the lock, but they're returned to the caller, who attaches their callbacks after the lock is released.

### 6.4 Release

`AsyncSemaphore.release()` at line 73:

```java
while (true) {
  CompletableFuture<Void> next = null;
  synchronized (lock) {
    while (!waiters.isEmpty()) {
      CompletableFuture<Void> w = waiters.pollFirst();
      if (!w.isDone()) { next = w; break; }
    }
    if (next == null) { available++; return; }
  }
  if (next.complete(null)) return;
  // else: waiter was cancelled in the gap; loop and try the next one
}
```

Three things going on:

1. **Drain stale waiters.** Inside the lock, skip any waiter that's already done (cancelled). `pollFirst` removes them so they don't sit in the queue forever.
2. **Transfer permit outside the lock.** `next.complete(null)` runs the caller's callbacks; we don't want our lock held during that.
3. **Outer loop on cancellation race.** Between `pollFirst` (inside lock) and `complete` (outside lock), the waiter could have been cancelled. `complete` returns `false` in that case; we loop and try the next waiter.

### 6.5 Close

`AsyncSemaphore.close()` at line 128 is idempotent. It drains the queue inside the lock and completes the drained waiters (with `CancellationException`) outside the lock. Permits already held by in-flight callers can still be `release()`d harmlessly — the counter accepts it.

### 6.6 Integration with `HttpDispatcher`

`HttpDispatcher.dispatch` at line 55 acquires a permit, composes with `send`, and registers a cancellation handler that propagates cancellation to the *permit*:

```java
CompletableFuture<Void> permit = permits.acquire();
CompletableFuture<HttpResponse<byte[]>> dispatched =
    permit.thenCompose(unused -> send(request));
dispatched.whenComplete((r, t) -> {
  if (t instanceof CancellationException) permit.cancel(false);
});
```

Without the permit cancellation, a slow-path waiter cancelled by the caller would stay live in the semaphore queue. `release()` would later "transfer" the permit by completing the waiter, but `thenCompose`'s function wouldn't run (its dependent is already cancelled), and `send` — which registers the `release()` `whenComplete` — would never fire. The permit would never come back.

### 6.7 Cases to call out

| Scenario | Behavior |
|---|---|
| 60 parallel async calls, mock server delays each 800 ms | Semaphore admits 50 immediately; the other 10 sit in FIFO. After ~800 ms the first batch releases and the 10 are admitted. Total wall-time ≈ 1.6 s. Server-side `peak_in_flight = 50`. |
| One slow caller, others fast | The slow call holds its permit; the fast ones go through the fast path on each release. |
| Caller cancels a slow-path waiter | Waiter is set done. `release()` skips it on the next pass. Net effect: zero permits leaked. |
| `MarketDataClient.close()` called mid-flight | `AsyncSemaphore.close()` rejects future `acquire()` calls with `CancellationException` and drains queued waiters. In-flight HTTP sends are not cancelled (until JDK 21 brings `HttpClient.close()` — see ADR-002). |

### 6.8 Verify locally

```bash
make publish
make mock-server        # terminal 2
make demo-concurrency   # terminal 3
```

`ConcurrencyApp` scripts 60 identical 800-ms-delayed responses, fires 60 `statusAsync()` calls in parallel, and after `allOf(...).join()` reads `/_admin/stats` from the mock server. Expectation: `peak_in_flight == 50` (exactly — not less, not more) and total wall-time ≈ 1.6 s (two batches of 50 + 10). Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/ConcurrencyApp.java`.

---

## 7. `Response<T>` + JSON parsing

### 7.1 `Response<T>` surface

`Response<T>` at `Response.java:26` is the carrier consumers see for every successful call.

Constructor (line 35) is private; only the package-private static factory `wrap(...)` at line 54 builds instances. Resources call it from their `executeAndWrap` (`UtilitiesResource.java:132`):

```java
return transport.executeAsync(spec)
    .thenApply(env -> Response.wrap(parser.parse(env, type), env, spec.format()));
```

Public accessors:

| Method | Returns | Notes |
|---|---|---|
| `data()` | `T` | Never null. Typed result of `parser.parse(envelope, T.class)`. |
| `rawBody()` | `byte[]` | **Defensive copy on every call** (line 67 — `return rawBody.clone()`). The constructor also clones on the way in (line 43). See §10.2. |
| `statusCode()` | `int` | 200, 203, or 404 today. |
| `requestId()` | `String?` | Cloudflare `cf-ray`, or null. |
| `requestUrl()` | `URI` | Absolute URL. |
| `isJson()` / `isCsv()` / `isHtml()` | `boolean` | Mutually exclusive (one is true). |
| `isNoData()` | `boolean` | `statusCode == 404`. |
| `saveToFile(Path)` | `void` | Writes `rawBody` verbatim. `UncheckedIOException` on write failure. |
| `toString()` | `String` | Status + format + bytes + redacted URL. **Never includes `data`.** See §10.2 / §10.3. |

The `Format` enum is package-private. Consumers query format via the boolean predicates, not the enum. That keeps `Format` free to grow new values without breaking compiled consumers (a `switch (response.format())` would otherwise be a source-compatibility hazard).

### 7.2 `JsonResponseParser`

`JsonResponseParser` at `JsonResponseParser.java:26` owns one `ObjectMapper` per `MarketDataClient`. Jackson mappers are thread-safe and expensive to construct, so we build one and reuse.

The parser is **resource-agnostic**: it doesn't know about `User`, `ApiStatus`, etc. Each `*Resource` self-registers its wire-format deserializers in its constructor via `parser.registerModule(...)`. The registration must complete before the first `parse(...)` call — satisfied today because resources are constructed before any HTTP traffic.

`parse(env, type)` at line 55:

```java
if (env.body().length == 0) {
  ErrorContext context = ErrorContext.forResponse(...);
  throw new ParseError("Empty response body from " + safeUri(...) + " — server returned 0 bytes ...", context);
}
try {
  return mapper.readValue(env.body(), type);
} catch (IOException e) {
  ErrorContext context = ErrorContext.forResponse(...);
  throw new ParseError("Failed to decode response from " + safeUri(...) + ": " + e.getMessage(), context, e);
}
```

The empty-body pre-check (issue #29) is §10.9. The `safeUri` in the error messages is §16's query-string redaction.

### 7.3 `ParallelArrays`

Most API endpoints return parallel arrays — N equal-length arrays of column values plus a leading `s` envelope status:

```json
{ "s": "ok",
  "symbol": ["AAPL", "MSFT"],
  "price":  [150.0,   400.0] }
```

`ParallelArrays.listDeserializer(fields, rowBuilder, wrapper)` at line 143 returns a `JsonDeserializer<T>` that:
1. Reads the body as a `JsonNode`.
2. Calls `zip(p, root, fields, rowBuilder)` to produce `List<ROW>`.
3. Calls `wrapper.apply(rows)` to produce the container record (e.g. `ApiStatus::new`).

The factory collapses the ~30-line boilerplate (extend `JsonDeserializer`, read tree, call zip, build record) to three pieces: column names, row builder, container wrapper. `UtilitiesResource.wireFormatModule()` at line 51 is the canonical use site:

```java
m.addDeserializer(
    ApiStatus.class,
    ParallelArrays.listDeserializer(
        List.of("service", "status", "online", "uptimePct30d", "uptimePct90d", "updated"),
        row -> new ServiceStatus(
            row.text("service"),
            row.text("status"),
            row.bool("online"),
            row.dbl("uptimePct30d"),
            row.dbl("uptimePct90d"),
            MarketDataDates.marketTimeFromEpochSecond(row.lng("updated"))),
        ApiStatus::new));
```

Future endpoints with parallel-arrays bodies follow this exact shape: three lines for the column list, one row builder lambda, one container constructor reference.

### 7.4 `ParallelArrays.zip`

`zip(p, root, fields, rowBuilder)` at line 69:

```java
String envelopeStatus = root.path("s").asText("");
if ("error".equals(envelopeStatus)) {
  throw new JsonMappingException(p, "API responded with error: " + errmsg);
}
if ("no_data".equals(envelopeStatus)) return List.of();
// validate columns: each field must be a present, equal-length array
// then build rows: rows.add(rowBuilder.build(new IndexedRow(arrays, i)));
return rows;
```

Three envelope cases:

- `s:"error"` — `JsonMappingException` carrying the server's `errmsg`. Caught and re-wrapped as `ParseError` by the parser. Consumer sees the server's diagnostic in the exception message.
- `s:"no_data"` — empty list. The container record's compact constructor copies it (`ApiStatus(List.of()) → services = List.copyOf(List.of())`). Combined with the 404 status, the consumer sees `response.isNoData() == true` and `response.data().services().isEmpty()`.
- Anything else (typically `"ok"`) — normal field validation runs.

Field validation enforces presence and equal-length. Any deviation throws `JsonMappingException` → `ParseError`. The error messages name the failing column, so a server-side regression that drops `online` or returns mismatched lengths produces an actionable diagnostic.

### 7.5 Strict `Row` accessors

`Row.text(field)`, `.bool(field)`, `.dbl(field)`, `.lng(field)` all throw `JsonMappingException` if the cell is null, missing, or the wrong JSON type (`ParallelArrays.java:197+`). The reasoning lives in the class Javadoc at line 30:

> The previous lenient behavior — substituting `""`, `false`, `0.0`, `0` for missing cells — masked real server bugs: e.g. a regression that dropped the `online` column would have silently flipped every service to `online=false`, propagating to `StatusCache` decisions and blocking retries across the board.

If a future endpoint legitimately has nullable columns, `textOr(field, default)` overloads can be added then — explicitly, per-column. Pre-emptive lenience is rejected.

### 7.6 Cases to call out

| Body | Status | Outcome |
|---|---|---|
| Parallel-arrays `s:"ok"` | 200/203 | `Response<T>` with typed `data()`. |
| `{"s":"no_data"}` | 404 | `Response<T>` with empty container, `isNoData() == true`. No exception. |
| `{"s":"error", "errmsg":"…"}` | any | `ParseError` containing the server's `errmsg`. |
| Truncated arrays (one column shorter than the others) | 200 | `ParseError` with the offending column name. |
| Empty body (proxy stripped) | 200 | `ParseError` with the "Empty response body" message. |
| Malformed JSON | 200 | `ParseError` wrapping Jackson's `IOException`. |
| Body with the wrong type for a column (e.g. `"true"` string where boolean expected) | 200 | `ParseError` from the strict `Row` accessor. |

### 7.7 Verify locally

```bash
make publish
make mock-server        # terminal 2
make demo-response      # terminal 3
```

`ResponseFeaturesApp` exercises: `isJson/isCsv/isHtml` mutual exclusion, the 404 + `{"s":"no_data"}` success envelope (`isNoData() == true`), `rawBody()` defensive-copy (mutating the returned array doesn't affect a second call), `saveToFile(...)` round-trip, and `toString()` log-safety (no `data`, query string redacted). Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/ResponseFeaturesApp.java`.

---

## 8. Sealed exception hierarchy

### 8.1 The seven permits

`MarketDataException.java:10`:

```java
public abstract sealed class MarketDataException extends RuntimeException
    permits AuthenticationError,
            BadRequestError,
            NotFoundError,
            RateLimitError,
            ServerError,
            NetworkError,
            ParseError {
  // ...
}
```

The list is fixed at **seven**. Per [ADR-002](adr/ADR-002-minimum-jdk-version.md), the JDK-17 floor was chosen specifically so this hierarchy could be sealed — adding an 8th permit in a future major version must break consumer `switch` exhaustiveness at compile time. That's the contract a sealed type promises; adding a permit without amending ADR-002 would forfeit it. A reviewer who sees a PR with an 8th permit (or a removed one) should check for an accompanying ADR amendment.

`RuntimeException` (not `Exception`) is the base — checked exceptions in resource façades would force consumers into ceremonial `try/catch` for every call. The sealed hierarchy gives them the same compile-time safety without the boilerplate.

### 8.2 `ErrorContext`

`ErrorContext.java:6`:

```java
public record ErrorContext(
    @Nullable String requestId, String requestUrl, int statusCode, Instant timestamp) {

  public static ErrorContext forResponse(String requestUrl, int statusCode, @Nullable String requestId, Instant timestamp);
  public static ErrorContext forNoResponse(String requestUrl, Instant timestamp);
}
```

`forResponse` is for HTTP-level errors (4xx/5xx). `forNoResponse` is for failures that didn't produce a response (`NetworkError` from `ConnectException`, `RateLimitError` from preflight). The latter sets `statusCode = 0` and `requestId = null` — the `0` sentinel is what `RetryPolicy.isRetriable` checks against to exclude `ServerError(statusCode=0)` from its 501–599 retriable range.

### 8.3 `MarketDataException` API

| Method | Returns | Notes |
|---|---|---|
| `getContext()` | `ErrorContext` | Full context with the **raw** `requestUrl`. Use this when the consumer has discretion to log/process the full URI. |
| `getRequestId()` | `@Nullable String` | `cf-ray` header value, or null. |
| `getRequestUrl()` | `String` | **Query-redacted** URL (line 44). The query string is replaced by `?…`. Mirrors `safeUri` in dispatchers/parsers — every getter that might land in ambient logs respects §16. |
| `getStatusCode()` | `int` | 0 for `forNoResponse`. |
| `getTimestamp()` | `Instant` | When the SDK observed the failure. |
| `getExceptionType()` | `String` | Simple class name (`"ServerError"`, etc.) for logging / dashboarding. |
| `getSupportInfo()` | `String` | Multi-line dump (line 75); ready to paste into a support ticket. |

`getSupportInfo()` formats with a fixed-width label column and a timestamp in `America/New_York`:

```
--- MARKET DATA SUPPORT INFO ---
request_id:     76a40b21d5e1c0a4-IAD
request_url:    /user/?…
status_code:    401
timestamp:      2026-05-21 09:12:34
message:        Authentication failed
exception_type: AuthenticationError
--------------------------------
```

The Eastern timezone is hard-coded (`MarketDataException.java:90`). See §10.14.

### 8.4 `HttpStatusMapper`

`HttpStatusMapper.map(statusCode, context, retryAfter)` at `HttpStatusMapper.java:43`:

```java
return switch (statusCode) {
  case 400 -> new BadRequestError("Bad request", context);
  case 401 -> new AuthenticationError("Authentication failed", context);
  case 404 -> new NotFoundError("Not found", context);
  case 429 -> new RateLimitError("Rate limit exceeded", context, null, retryAfter);
  default -> mapByRange(statusCode, context, retryAfter);
};
```

`mapByRange` at line 57 handles the broad buckets:

- 500–599 → `ServerError` (retriable per `RetryPolicy.isRetriable`).
- 4xx (other than 401/404/429) → `BadRequestError` with the actual status in the message.
- 3xx → `BadRequestError` "Unhandled redirect" (the `HttpClient` is configured with `NORMAL` redirect following; a 3xx surviving means the redirect couldn't be followed).
- 1xx → `BadRequestError` "Unexpected informational response" (defensive; `HttpClient` handles `100 Continue` itself).
- Negative / >599 → `BadRequestError` "Unexpected HTTP status".

**The `case 404 -> NotFoundError` is currently dead code.** `HttpTransport.routeAndEnvelope` short-circuits all 404 responses to the success envelope (line 304) before `map(...)` is consulted. Resolving this — either by requiring an `s:"no_data"` body before routing 404 as success, or by removing `NotFoundError` from the sealed permits via an ADR amendment — is a follow-up. See PR.md "Out of scope / known caveats".

### 8.5 Consumer-side routing

A consumer routes by the sealed hierarchy. With JDK 21+ pattern switches:

```java
try {
  client.utilities().user();
} catch (MarketDataException e) {
  String label = switch (e) {
    case AuthenticationError a -> "AUTH";
    case BadRequestError b     -> "BAD_REQUEST";
    case NotFoundError n       -> "NOT_FOUND";
    case RateLimitError r      -> "RATE_LIMITED (retryAfter=" + r.getRetryAfter() + ")";
    case ServerError s         -> "SERVER (status=" + s.getStatusCode() + ")";
    case NetworkError n        -> "NETWORK";
    case ParseError p          -> "PARSE";
  };
  // routed
}
```

The switch is exhaustive: no `default` clause is needed because the sealed hierarchy is closed. On the SDK's minimum JDK 17, the same routing uses an `instanceof` chain.

### 8.6 Cases to call out

| Wire condition | Mapped exception |
|---|---|
| HTTP 401 | `AuthenticationError`. |
| HTTP 400 | `BadRequestError`. |
| HTTP 403, 422, 405, etc. | `BadRequestError` with the actual status in the message. |
| HTTP 429 + `Retry-After: 60` | `RateLimitError` with the parsed Duration on `getRetryAfter()`. **Not retried** by the policy. |
| HTTP 500 | `ServerError`. **Not retriable** (statusCode == 500 is not in [501, 599]). |
| HTTP 503 + `Retry-After: 5` | `ServerError` with `getRetryAfter().isPresent()` and `getStatusCode() == 503`. **Retriable**. |
| Preflight blocked (snapshot remaining=0) | `RateLimitError` with `statusCode == 0` and `requestId == null`. |
| Connection refused | `NetworkError` with the `ConnectException` as cause. |
| 200 + body Jackson can't parse | `ParseError` with the underlying `IOException` as cause. |

### 8.7 Verify locally

```bash
make publish
make mock-server        # terminal 2
make demo-exceptions    # terminal 3
```

`ExceptionsApp` round-trips each sealed permit through a scripted scenario: 401 → `AuthenticationError`, 400 → `BadRequestError`, 429 + `Retry-After` → `RateLimitError`, 500 → `ServerError` (1 server-side request, no retries), 503×4 → `ServerError` after ~7 s (3 retries with exponential backoff), malformed JSON → `ParseError`, empty body → `ParseError` with the precise message, connection refused → `NetworkError` after retries. The final scenario uses an `instanceof` chain over the sealed type to prove the routing surface (the JDK 21+ pattern-switch equivalent is in source comments as reference). Source: `examples/consumer-test/src/main/java/com/marketdata/consumer/ExceptionsApp.java`.

`NotFoundError` is currently unreachable end-to-end (see §8.4 — `HttpTransport.routeAndEnvelope` short-circuits all 404 to the success branch). The demo notes this explicitly and skips that scenario rather than fabricating it.

---

## 9. Configuration & logging

### 9.1 Cascade rungs

Already covered structurally in §3.2 ("Configuration cascade"). The order is: **explicit → env var (`MARKETDATA_*`) → `.env` → default**.

`Configuration.pickFirst(...)` (line 220) and `pickFirstOrDefault(...)` (line 229) walk the rungs in that order. The first non-null, non-blank candidate wins. Blank strings (`"   "`) are treated as absent — a `.env` file with `MARKETDATA_TOKEN=` should not produce a blank token.

`EnvVars.ALLOWED_KEYS` is the explicit whitelist passed to `DotEnvLoader.load(...)`. Any key in the `.env` that isn't on the list is silently skipped — that's the §16 minimization rule (the SDK doesn't read env vars it doesn't know about).

`EnvVars.systemLookup()` returns a `Function<String, @Nullable String>` that only reads from the allowed set, with a `null` for unknown keys. SDK code that needs to read env vars goes through this function, never `System.getenv()` directly.

### 9.2 `MarketDataLogging.configure`

`MarketDataLogging.java:63`:

```java
static void configure(@Nullable String levelSpec) {
  Level requested = parseLevel(levelSpec);
  if (configured.get()) {
    // idempotency: first install wins
    return;
  }
  Logger sdkLogger = Logger.getLogger(SDK_LOGGER_NAME);
  if (sdkLogger.getHandlers().length > 0 || sdkLogger.getLevel() != null) {
    // consumer pre-config detected — back off entirely
    // DO NOT latch `configured` here
    return;
  }
  if (!configured.compareAndSet(false, true)) return;  // lost the race
  Handler handler = new ConsoleHandler();
  handler.setFormatter(new CanonicalLogFormatter());
  handler.setLevel(Level.ALL);
  sdkLogger.addHandler(handler);
  sdkLogger.setUseParentHandlers(false);
  sdkLogger.setLevel(requested);
}
```

Two paths matter:

1. **First call, no consumer pre-config.** Install the SDK's `ConsoleHandler` + `CanonicalLogFormatter`. Set `useParentHandlers = false` so the JDK's default root handler doesn't re-emit each record with `SimpleFormatter`'s shape. Latch `configured` so subsequent calls are no-ops.
2. **Consumer pre-config detected.** If the consumer (or another library) already attached a handler or set a level on `com.marketdata.sdk` before `MarketDataClient` was constructed, the SDK installs nothing. Crucially, `configured` is **not** latched on this path. The consumer might later remove their handler / clear their level — a subsequent `configure(...)` call should be allowed to install the SDK defaults then. Latching here would freeze the SDK out for the lifetime of the process.

`parseLevel(...)` at line 113 maps `DEBUG/INFO/WARNING/ERROR` (case-insensitive) to JUL levels (`FINE/INFO/WARNING/SEVERE`). Unknown values fall back to `INFO` with a logged warning — silent fallback was the worst of both worlds (consumer types something wrong, sees `INFO` output instead of the `DEBUG` they expected, with no breadcrumb).

### 9.3 `Tokens.redact`

`Tokens.java:16`:

```java
static String redact(@Nullable String token) {
  if (token == null || token.length() <= 8) return "***…***";
  return "***…***" + token.substring(token.length() - 4);
}
```

The 8-char hinge is the design decision. At ≤ 4 chars, the last-4 *is* the full token. At 5–7 chars, the last-4 is 57–80% of the value — still too leaky. At ≥ 9 chars, the last-4 is at most 44% of the token, enough material for a human to disambiguate which token is in use without enabling someone with log access to use it.

The function is called from:
- `MarketDataClient.toString()` (line 199) and the INFO log (line 75).
- Tests that verify redaction.

It is **not** called from exception messages — that path goes through `safeUri` instead, which strips entire query strings. Tokens never make it onto query strings via SDK code (they're always in `Authorization` headers), so there's no SDK-emitted message that needs `Tokens.redact` for a query-string token. The two surfaces handle disjoint risks.

### 9.4 Query-string redaction

`HttpDispatcher.safeUri(URI)` at `HttpDispatcher.java:146`:

```java
String path = uri.getPath();
if (path == null) return uri.toString();          // opaque URI fallback
return uri.getRawQuery() != null ? path + "?…" : path;
```

Used everywhere a URL might land in ambient logs:
- `HttpDispatcher` request/response log lines.
- `HttpTransport.routeAndEnvelope` exception log.
- `MarketDataException.getRequestUrl()` (via `redactQuery` mirroring the same convention — see §10.3).
- `Response.toString()` (line 147).
- `JsonResponseParser.parse` error message (line 67, 82).

The full URI is preserved on `ErrorContext.requestUrl` for consumer-side diagnostic access via `e.getContext().requestUrl()`. Ambient logs ≠ exception context.

### 9.5 `CanonicalLogFormatter`

The §7 shape: `{timestamp} - {logger_name} - {level} - {message}`. Implementation at `CanonicalLogFormatter.java`. Tested by `CanonicalLogFormatterTest`.

### 9.6 Cases to call out

| Scenario | Behavior |
|---|---|
| No env vars, no `.env`, no explicit args | Demo mode (apiKey null). `baseUrl` and `apiVersion` fall through to defaults. INFO logger. |
| `MARKETDATA_BASE_URL` set, explicit `baseUrl = null` | env var wins. |
| `MARKETDATA_BASE_URL` set, explicit `baseUrl = "https://prod"` | explicit wins. |
| `.env` carries `MARKETDATA_TOKEN`, env var doesn't, no explicit | `.env` wins. |
| `.env` is unreadable | Warning collected. Logged after `configure(...)` runs; attached as suppressed if `resolve` throws downstream. |
| Consumer ran `Logger.getLogger("com.marketdata.sdk").addHandler(myHandler)` before `new MarketDataClient(...)` | SDK detects, installs nothing, `configured` stays `false`. |
| Consumer later removes their handler, then constructs a second client | Now SDK installs its handler — first-install wins from that point. |
| `MARKETDATA_LOGGING_LEVEL=lolwut` | `INFO` with a logged warning. |

### 9.7 Verify locally

Configuration cascade and the token-redaction hinge are exercised by `make demo-config` (same demo as §3.4 — `DemoAndConfigApp`).

End-to-end logging coverage is not in a dedicated demo today: there is no `LoggingApp` that toggles `MARKETDATA_LOGGING_LEVEL` between runs and verifies the canonical-format output or the consumer-pre-config detection. A reviewer who wants to eyeball the logger shape can:

```bash
MARKETDATA_LOGGING_LEVEL=DEBUG make demo-live
```

and inspect stderr for the `{timestamp} - com.marketdata.sdk - {level} - {message}` shape and the FINE-level retry attempts. The consumer-pre-config detection is covered by `MarketDataLoggingTest` in unit tests rather than a runnable demo.

---

## 10. Subtle corners (issue-driven)

These are the spots a reviewer would otherwise rabbit-hole on. Each one names the file, what looks weird, and why.

### 10.1 Token redaction hinge at 8 characters

`Tokens.java:17`. Tokens ≤ 8 chars are fully masked (`***…***`); >8 chars get the last-4 suffix (`***…***ABCD`). The hinge is set because for short tokens the suffix would be most of the value: at 4 chars it's the whole thing; at 5–7 it's 57–80%. >8 caps the suffix's share of the token at 44%. Reviewer: don't be tempted to "always show the last 4" — that defeats the whole point for short tokens.

### 10.2 Defensive copy on both ends of `rawBody`

`Response.java:43` (constructor clones in) and `Response.java:67` (getter clones out). Belt-and-suspenders. The wrap factory passes `envelope.body()` directly; if a later refactor accidentally lets the envelope's body be a mutable buffer, the constructor's clone is the firewall. The getter's clone is what prevents a consumer's `byte[] b = response.rawBody(); b[0] = 'X';` from poisoning the next `rawBody()` call. The `toString` redaction (§10.3) is a separate firewall on a separate surface.

### 10.3 Query-string redaction in two layers

There are two redaction surfaces:

- **Ambient logs** — `HttpDispatcher.safeUri(URI)` (line 146). Used wherever the SDK *itself* logs a URL. Replaces the query string with `?…`.
- **Exception getter** — `MarketDataException.getRequestUrl()` (line 44, via `redactQuery(String)`). Used when a consumer's `logger.error("Failed: " + e.getRequestUrl())` would otherwise persist the raw URL.

Both surfaces converge on the same shape (`path?…`) but live in different places because the URL types differ (`URI` for fresh dispatches, `String` for stored `ErrorContext.requestUrl`). `getContext().requestUrl()` still exposes the raw URL — that's the discretionary path for diagnostic code that knows what it's doing.

### 10.4 Strict deserialization in `ParallelArrays.Row`

`ParallelArrays.java:160+`. Every accessor throws `JsonMappingException` on null, missing, or wrong-type cells. The class Javadoc explains why: a previous lenient version silently substituted sentinel values, masking server-side regressions (a dropped `online` column flipping every service to `online=false`). A reviewer who's tempted to add `textOr(field, default)` overloads should do so per-column-per-endpoint, only when the field is *contractually* nullable on that endpoint — not as a global escape hatch.

### 10.5 Status path canonicalization to trailing slash (issue #18)

`StatusCache.java:130`. `lookupService` does longest-path-prefix matching. Without trailing-slash normalization, a key `/v1/stock` would falsely match `/v1/stocks/quotes/AAPL/` (path-component boundary not respected). Canonicalization happens at *snapshot construction* (line 161) so keys are stored with a trailing slash; the lookup also appends a slash to the input path before comparing. One malformed/truncated server-side entry can no longer block retries for an unrelated service.

### 10.6 `Retry-After` 10-minute cap (issue #21)

`RetryPolicy.java:44`. A compromised or buggy backend that emits `Retry-After: 9999999999` would otherwise freeze the next attempt for ~292 billion years inside `CompletableFuture.delayedExecutor`. The cap is intentionally generous (10 minutes) so legitimate "come back in an hour" hints would still… wait, no, anything above 10 min falls back to exponential. The raw value remains visible on `ServerError.getRetryAfter()` so consumers can decide for themselves (surface to a human, schedule a real cool-off via a job runner). The cap only controls the SDK's *automatic* wait.

### 10.7 Re-check after `triggerRefresh` in `StatusCache` (issue #19)

`StatusCache.java:69`. When a refresh is needed, `triggerRefresh()` fires the fetcher and returns immediately — production fetchers go through `HttpTransport`, which is async. But in tests, a stub fetcher returning `CompletableFuture.completedFuture(...)` completes *synchronously* — the `whenComplete` populating `snapshot` runs inside the same call. Without the post-trigger re-read, the local `snap` variable is still the null we captured before `triggerRefresh`, and `check` always answers ALLOW on a cold start even when the cache *now* says BLOCK. The fix is one line: re-read `snapshot.get()` after `triggerRefresh()`. Production path is unaffected (the future is genuinely async; the re-read just observes the still-null snapshot).

### 10.8 Printable-ASCII validation of `apiKey` rejecting CRLF (issue #23)

`Configuration.java:189`. Tokens with CR/LF embedded — usually from a `.env` file edited on Windows or copy-pasted from an email — would later be rejected by `HttpRequest.Builder#header` with a generic `IllegalArgumentException` from the bowels of `HttpClient`, far from the actual configuration source. Validating at constructor time produces a clear, sourced message: "apiKey contains an invalid character at offset N (code point 0xXX)". The rule (`[0x20, 0x7E]`) is permissive enough for every legitimate token shape while ruling out NUL, DEL, high-bit bytes, and CR/LF.

### 10.9 Empty-body pre-check before Jackson (issue #29)

`JsonResponseParser.java:60`. A zero-length body produces a generic `"No content to map"` from Jackson — diagnostically thin, often confusing in the presence of a body-stripping proxy. The pre-check produces a précis message: `"Empty response body from /user/?… — server returned 0 bytes (a proxy may have stripped the payload, or the endpoint replied without one)"`. The message names the actual symptom and the most common cause.

### 10.10 Full IOException cause-chain walk for retry classification (issue #15)

`RetryPolicy.java:173`. `HttpClient` under HTTP/2 multiplexing — particularly on certain JDK versions — can present an `IOException` nested under an `ExecutionException` or `CompletionException` wrapper that `HttpDispatcher`'s single-level `unwrap` doesn't peel. Without the walk, legitimate transport failures fall out of retry silently — the SDK loses §9 resilience under exactly the load conditions that need it.

The walk is depth-capped at 16 and detects self-cycles. Both are defensive: `Throwable.getCause()` cycles are theoretically impossible but cheap to guard.

### 10.11 Preflight bypass on server-hinted retry

`HttpTransport.java:184` + `:206`. A retry following a `503 + Retry-After: 5` is server-orchestrated — the server has told us "come back at `now + 5s`". Our local rate-limit snapshot (whose `reset` may be unrelated and hours in the future) must not veto that directive. The bypass detects this exact case: `previousCause instanceof ServerError server && server.getRetryAfter().isPresent()`. Only ServerError-with-parsed-Retry-After qualifies — a generic 503 retry still goes through preflight.

### 10.12 Deferred `StatusCache` construction via `AtomicReference` supplier

`MarketDataClient.java:90` + `HttpTransport.java:60`. The cache's fetcher uses `utilities.statusAsync()`, which goes through `HttpTransport`. So:

- Transport needs cache (to gate retries).
- Cache needs `utilities`.
- `utilities` needs transport.

Chicken-and-egg. The resolution is `cacheRef::get` — a `Supplier<StatusCache>` passed to the transport that returns `null` until the cache is constructed below. `HttpTransport.cacheAllowsRetry` (line 240) handles `null` gracefully by short-circuiting to `true`. This means:

- During construction (before `cacheRef.set(...)`), the transport behaves as if there's no cache.
- After construction (`cacheRef.set(...)`), every subsequent call sees the cache.

The startup validation (`runStartupValidation` → `utilities.validateAuth`) runs *after* `cacheRef.set(...)` but uses `RetryPolicy.noRetry()` anyway, so the cache wouldn't be consulted on its retry path even if it weren't there.

### 10.13 `joinSync` unwraps `CompletionException`

`HttpTransport.java:264`. `CompletableFuture.join()` wraps any failure as `CompletionException`. Per ADR-006 the SDK's sync contract is to surface `MarketDataException` directly. `joinSync` catches `CompletionException`, calls `asRuntime(e.getCause(), clock)`, and re-throws.

`asRuntime(cause)` (line 399) has three branches:

- `cause` is `MarketDataException` → return it (the common path).
- `cause` is some other `RuntimeException` → return it (defensive — shouldn't happen in production).
- Anything else → wrap as `NetworkError` with `forNoResponse` context.

The two non-`MarketDataException` branches are unreachable from the public API today; they exist so a future bug doesn't surface as a confusing `CompletionException` to the consumer.

### 10.14 `getSupportInfo` timestamps hard-coded to America/New_York

`MarketDataException.java:90`:

```java
private static final DateTimeFormatter EASTERN_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("America/New_York"));
```

The market-data API operates in Eastern time. Support staff reading a pasted `getSupportInfo()` dump should be able to correlate against their tooling without timezone math. The `Instant` is preserved on `ErrorContext.timestamp()` for consumers who want UTC. This is a deliberate divergence from "always use UTC in interfaces" — the support-info dump is for humans, not machines, and the humans live in ET.
