# ADR-004: HTTP Client

## Status

Proposed — under discussion.

## Context

The SDK needs an HTTP client to make REST requests to the Market Data
API. The choice affects:

- Runtime dependency footprint imposed on consumers
- Async API shape (the underlying client's async primitive bleeds into
  the SDK's async surface — see ADR-006)
- Connection pooling and HTTP/2 support
- Compatibility with the requirements doc's mandated 99-second request
  timeout, 2-second connect timeout, and 50-request concurrency pool

This ADR depends on [ADR-002 (Minimum JDK)](./ADR-002-minimum-jdk-version.md):

- If ADR-002 lands on **JDK 11+**, all three options below are viable.
- If ADR-002 lands on **JDK 8**, `java.net.http.HttpClient` is
  unavailable and the options collapse to OkHttp and Apache HttpClient
  only.

The discussion below assumes JDK 11+ as the more interesting case. If
the team chooses JDK 8 in ADR-002, simply read this ADR as a comparison
between Options B and C.

The requirements doc (§1.1, §10) mandates connection pooling and the
fixed 99s/2s timeouts. All three options support these natively.

## Options Considered

### Option A — `java.net.http.HttpClient` (JDK built-in, 11+)

The HTTP client shipped with the JDK since Java 11.

**Pros**

- **Zero runtime dependencies.** This is the dominant argument for an
  SDK trying to stay light. Every other option pulls in transitive
  jars (OkHttp pulls `okio` and `kotlin-stdlib`; Apache pulls
  `httpcore5` and friends).
- HTTP/2 support is native and on by default.
- Connection pooling is automatic; no configuration required.
- Async API is built around `CompletableFuture`, which the requirements
  doc (§Language conventions table) specifies as Java's async pattern.
- Plays well with virtual threads on JDK 21+ runtimes (synchronous
  send blocks the calling thread, which on a virtual thread costs
  almost nothing).
- Per-request timeout is a first-class API (`HttpRequest.Builder#timeout`).
- Standard library — no version-skew or CVE-tracking burden.

**Cons**

- Less configurable than OkHttp or Apache. No interceptor/middleware
  pattern; no built-in request/response logging beyond `java.util.logging`;
  no pluggable retry. We need to hand-roll those — which is what the
  requirements doc directs anyway (§9, §7), but it does mean fewer
  free wins.
- Smaller third-party ecosystem. Adapters for libraries like Resilience4j
  exist but are less polished than for OkHttp/Apache.
- API surface has rough edges: form-encoded bodies require manual
  serialization; `BodyPublishers`/`BodyHandlers` is a bit verbose.
- Connection pool is not directly observable (no metrics out of the
  box). Important for debugging at scale; less critical for an SDK
  client whose 50-request semaphore caps in-flight work anyway.

### Option B — OkHttp 5

Square's open-source HTTP client. Used by huge numbers of Java/Kotlin
applications.

**Pros**

- Battle-tested at Square scale. Mature, well-documented.
- Built-in interceptor pipeline makes adding logging, request IDs, and
  custom headers trivial.
- Built-in HTTP/2, connection pooling, transparent gzip.
- Excellent timeout configuration, including per-request overrides.
- Wide community familiarity; many existing Java SDKs ship with OkHttp.

**Cons**

- **Pulls in `kotlin-stdlib` as a transitive dependency** (~1.5 MB).
  OkHttp 4 was rewritten in Kotlin; OkHttp 5 continues that. This
  contradicts the zero-deps direction and parallels the cost we
  rejected in ADR-001 Option C.
- Two additional transitive deps: `okio` and `kotlin-stdlib`.
- Async pattern is callback-based (`Call.enqueue(Callback)`). To
  match the requirements doc's CompletableFuture mandate we'd write
  a thin adapter — workable, but it's adapter glue we wouldn't write
  with `java.net.http`.
- Adds a CVE surface we need to track and respond to. Standard library
  CVEs are JDK CVEs; OkHttp CVEs are ours to manage in a release.

### Option C — Apache HttpClient 5

The classic enterprise-grade Java HTTP client.

**Pros**

- Battle-tested across nearly every enterprise Java environment.
- Highly configurable: connection manager, request retry handler,
  request/response interceptor chain, advanced TLS configuration.
- Both sync and async APIs (`CloseableHttpClient` and
  `CloseableHttpAsyncClient`).
- HTTP/2 supported (with explicit configuration).
- No Kotlin transitive dependency.

**Cons**

- Heavier dependency tree than OkHttp: `httpclient5`, `httpcore5`,
  `httpcore5-h2`, `commons-codec`, `slf4j-api`. The actual byte cost
  is comparable to OkHttp's (Kotlin stdlib is also ~1.5 MB) but the
  Apache jars have always felt enterprise-y to the consumer.
- Configuration is verbose. Setting up a basic client with timeouts,
  pooling, and interceptors takes meaningfully more code than
  `java.net.http` or OkHttp.
- The async client's API is more complex (futures + callbacks +
  `IOReactor`). We'd hide it behind our own facade.
- HTTP/2 requires explicit opt-in via `H2Config`.

## Claude's Recommendation

**Option A (`java.net.http.HttpClient`).**

The deciding factors:

1. Zero runtime dependencies is a real, recurring benefit for SDK
   consumers. Every dep we ship is a potential classpath collision,
   CVE-response burden, and version-skew headache. The standard
   library has none of those.
2. The requirements doc tells us to hand-roll retry, status caching,
   rate limiting, and the concurrency pool anyway. The headline
   features of OkHttp and Apache (interceptor pipelines, retry
   handlers) don't unlock anything we wouldn't already write
   ourselves.
3. `java.net.http` is async-native via `CompletableFuture`, which
   matches the requirements doc's Java async pattern exactly. OkHttp
   and Apache require adapter code to fit that mandate.
4. The biggest knock against `java.net.http` is "less configurable."
   For an SDK with a tightly defined behavior set (one base URL, one
   auth scheme, one set of timeouts, one set of headers) we don't
   actually need that configurability.
5. Both Option B and Option C add weight without unlocking
   capabilities the requirements doc demands.

The strongest counter-recommendation is **Option B (OkHttp)** if the
team has deep OkHttp expertise and wants a mature interceptor pipeline
for logging and instrumentation. That's defensible. The kotlin-stdlib
transitive cost is the main thing it has to justify.

**Option C (Apache HttpClient)** is the right answer in environments
where Apache is already the standard or where deep TLS/proxy
configuration matters. Neither is a strong argument for this SDK.

## Decision

*To be filled in by the team.*

## Consequences

*To be filled in once a decision is made.*

- **A (`java.net.http`):** Zero HTTP runtime deps. Hand-roll
  retry/logging/observability. Async via `CompletableFuture` directly.
  Requires JDK 11+ from ADR-002.
- **B (OkHttp 5):** `okhttp:5.x` + `okio:3.x` + `kotlin-stdlib:1.9.x`
  on the dependency tree. Interceptor-based logging and metrics. Async
  callback API needs a CompletableFuture adapter.
- **C (Apache HttpClient 5):** `httpclient5` + `httpcore5` (+ `h2`)
  on the dependency tree. Verbose configuration upfront; rich tuning
  knobs available later if needed.

## References

- [JEP 321: HTTP Client (Standard) — JDK 11](https://openjdk.org/jeps/321)
- [java.net.http JavaDoc](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/package-summary.html)
- [OkHttp 5 documentation](https://square.github.io/okhttp/)
- [Apache HttpClient 5 documentation](https://hc.apache.org/httpcomponents-client-5.4.x/)
- [Market Data SDK Requirements §10 — Timeouts](../sdk-requirements.md)
- [ADR-002 — Minimum JDK Version](./ADR-002-minimum-jdk-version.md)
