# ADR-006: Async API Surface

## Status

Accepted.

## Context

The requirements doc fixes Java's async pattern: `CompletableFuture`
(see the language conventions table). What it does *not* fix is **how
much** of the SDK's public surface should expose async variants:

- Every endpoint, always?
- Only the endpoints with built-in fan-out concurrency
  (`stocks.candles` for intraday >1y ranges, `options.quotes` for
  multiple symbols — both required by §12)?
- Async-only, with sync as thin wrappers?
- Sync-only, with consumers wrapping in `CompletableFuture.supplyAsync`
  if needed?

This is a public-API decision, so it's hard to reverse without breaking
changes. Several other choices feed into it:

- **ADR-004 (HTTP client)** — `java.net.http.HttpClient` is
  async-native via `CompletableFuture`; OkHttp is callback-async;
  Apache has both. Whichever underlies the SDK, the ergonomic async
  primitive is `CompletableFuture`.
- **Virtual threads (JDK 21+)** — when consumers run on a virtual-
  thread runtime, blocking sync calls are essentially free. This
  *reduces* the marginal value of an async API for those consumers,
  but they still pay for the SDK API shape we ship.
- **The 50-request concurrency pool (§12)** — implemented via a
  semaphore acquired around every HTTP send. Naturally fits an async
  pipeline; works fine with sync callers too.

Reactive Streams (RxJava, Project Reactor) are explicitly out of scope:
the requirements doc names `CompletableFuture`, and forcing reactive
on consumers would contradict §Language-Idiomatic Design.

## Options Considered

### Option A — Sync only

Every public method blocks. Async is the consumer's problem
(`CompletableFuture.supplyAsync(() -> client.stocks.quotes("AAPL"))`).

**Pros**

- Smallest public API surface. One method per endpoint.
- Simplest to document and to reason about.
- Easiest to test.
- Virtual threads on JDK 21+ make sync scaling effectively free for
  modern consumers.

**Cons**

- Consumers on JDK <21 wrapping in `CompletableFuture.supplyAsync` end
  up using `ForkJoinPool.commonPool()` by default, which is shared
  across the JVM and badly suited to I/O-bound work. Many won't know
  to provide a custom executor.
- Hides the fact that the underlying HTTP client (likely
  `java.net.http`) is already async. We'd be doing extra work to
  *prevent* async from showing through.
- Doesn't satisfy the natural expectation of a 2026 Java SDK, which
  almost always offers some async surface.

### Option B — Sync + async per method (full parity)

Every endpoint exposes both `quotes(...)` (sync) and `quotesAsync(...)`
returning `CompletableFuture<T>`.

**Pros**

- Predictable. No "why is this async but that one isn't?" surprises.
- Idiomatic for Java SDKs in 2026 (AWS SDK v2 does this; Google Cloud
  Java client libraries do this with both sync `Client` and async
  `Client`).
- Cheap to implement: the underlying HTTP client is async, so the
  natural shape is *async-first internally* with sync methods that
  call `.join()` on the future. Both surfaces are produced from one
  implementation.
- Consumers pick what they need without library-side gatekeeping.

**Cons**

- Public method count roughly doubles.
- Documentation surface roughly doubles.
- Test surface roughly doubles (though most async tests are mechanical
  variants of sync tests).

### Option C — Selective async

Only the fan-out endpoints (`stocks.candles` for >1y intraday ranges
and `options.quotes` for multi-symbol batches, per §12) expose async.
Other endpoints are sync only.

**Pros**

- Async is offered exactly where the SDK does internal concurrency
  itself, and nowhere else.
- Smaller public surface than Option B.

**Cons**

- Inconsistent. Callers must remember which endpoints have async and
  which don't.
- The "fan-out endpoints are async because they need to be" reasoning
  is internal to the SDK; from outside, all endpoints look like they
  could benefit from async equally (they all do I/O).
- Awkward for consumers building event-driven systems who want async
  uniformly.
- Likely to grow over time as more endpoints get async added one at a
  time, eventually arriving at Option B with extra commits.

### Option D — Async-primary with sync wrappers

Every method returns `CompletableFuture<T>`. Sync variants exist as
thin `.join()` wrappers — possibly named `quotesBlocking(...)` or
moved to a separate `BlockingClient` facade.

**Pros**

- The async pipeline is the canonical path; sync is the special case.
- Matches some modern reactive-leaning SDKs.

**Cons**

- Inverts user expectations. Most Java users reach for the sync
  signature first.
- The "2–3 lines of code" requirement (requirements doc §Easy Default
  Requests) becomes harder to satisfy ergonomically. `client.stocks
  .quotes("AAPL").join()` is one extra concept (and `.join()` rethrows
  unchecked exceptions in non-obvious ways).
- Forces every casual user to learn `CompletableFuture` semantics
  even if they never wanted async.
- No major Java SDK in this space ships async-primary-by-default.

## Claude's Recommendation

**Option B (sync + async per method, full parity).**

The deciding factors:

1. The requirements doc explicitly names `CompletableFuture` as Java's
   async pattern. Option A doesn't ship one; Option D ships one
   awkwardly; Option C ships it inconsistently. Option B is the only
   option that delivers what the spec implies.
2. Implementation cost is low. The underlying HTTP client returns
   `CompletableFuture<HttpResponse<…>>`. Internal logic is async-first;
   sync methods are one-line wrappers that call `.join()` and unwrap
   `CompletionException` to surface the cause. Both surfaces from one
   implementation.
3. Doubling the method count sounds expensive but it's mechanical and
   self-consistent. Each sync method has exactly one corresponding
   `…Async` method with identical parameters and return-type-wrapped-
   in-CompletableFuture.
4. AWS SDK for Java v2 ships separate sync and async clients;
   Google Cloud Java ships both surfaces. We're not inventing a
   pattern — we're matching the dominant convention.
5. Option C ages poorly. As soon as a customer asks for async on a
   non-fan-out endpoint, we're adding it ad-hoc. We'd end up at
   Option B by accident, with worse consistency.

The strongest counter-recommendation is **Option A (sync only)**. The
case for it is "the SDK is simpler; sophisticated consumers can wrap
themselves." That's fine if we believe most users will be on virtual
threads. We don't have evidence either way — but absent that evidence,
the Java SDK convention in 2026 is to provide async natively, and the
implementation cost of doing so is low.

A reasonable middle path: ship Option A in v1.0 and Option B in
v1.1. This is *not* costless — adding async methods later requires
either Javadoc churn or a `…Async` naming scheme bolted on after the
fact — but it's open to the team.

## Decision

**Option B — Sync + async per method (full parity).** Every public
endpoint method exposes both a sync variant and an `…Async` variant
returning `CompletableFuture<T>`.

The team's reasoning: as a consumer it's valuable to have both options
ergonomically available without library-side gatekeeping (Taylor); full
sync+async parity is essentially the standard for modern Java SDKs in
2026 (Lucas, citing AWS SDK v2 / Google Cloud Java parallels). The
doubled test surface is acceptable cost — the SDK is not expected to
undergo constant modification, so the CI-time impact is manageable.

Implementation: internal logic is **async-first**. The underlying HTTP
client (`java.net.http`, per ADR-004) returns
`CompletableFuture<HttpResponse<…>>` natively. Sync methods are thin
wrappers that call `.join()` and unwrap `CompletionException` to
surface the underlying cause directly. Both surfaces share the same
validation, retry, rate-limit, and concurrency-pool logic — no
parallel implementations.

Options A (sync only), C (selective async), and D (async-primary with
sync wrappers) were considered but rejected.

## Consequences

Follow-on work implied by each option. The chosen option is marked.

- **A (sync only):** All public methods block. Internal HTTP layer
  may still be async; we just don't expose it. No `…Async` methods,
  no `CompletableFuture` in the public API except possibly on
  `MarketDataClient.close()`.
- **B (chosen):** Every endpoint method has a sibling
  `…Async(...)` returning `CompletableFuture<T>`. Internal logic is
  async-first; sync wrappers call `.join()` and unwrap
  `CompletionException`. Both surfaces share validation, retry, rate-
  limit, and concurrency-pool logic.
- **C (selective async):** Async only on the §12 fan-out endpoints.
  All others sync only. Documented as a rule with the rationale.
- **D (async-primary):** All public endpoint methods return
  `CompletableFuture<T>`. Sync variants either don't exist or live on
  a separate `BlockingClient` view of the same client.

## References

- [Market Data SDK Requirements §Language conventions, §12 Concurrency Helpers](../sdk-requirements.md)
- [`java.util.concurrent.CompletableFuture`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [AWS SDK for Java v2 — sync vs async clients](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/asynchronous.html)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — context
  on the virtual-threads-vs-async tradeoff
- [ADR-002 — Minimum JDK Version](./ADR-002-minimum-jdk-version.md)
- [ADR-004 — HTTP Client](./ADR-004-http-client.md)
