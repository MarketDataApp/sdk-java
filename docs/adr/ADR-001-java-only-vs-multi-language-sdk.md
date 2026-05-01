# ADR-001: Java-Only vs Multi-Language SDK

## Status

Accepted.

## Context

The Market Data Java SDK is starting from a clean repository. Before any
code is written we need to decide the language scope of the artifact:
should it be pure Java, or should it incorporate Kotlin in some form?

This is a foundational decision because it affects:

- The transitive dependency footprint imposed on every consumer
- Build complexity, contribution barrier, and long-term maintenance cost
- Which language idioms the public API can natively express
- Compatibility with the canonical
  [SDK Requirements](../sdk-requirements.md), which pin Java to
  `Exceptions | CompletableFuture | POJOs | camelCase`

The Python SDK ([`sdk-py`](../../../sdk-py)) is single-language; this ADR
also implicitly decides whether to set a precedent of one-language-per-SDK
across the Market Data SDK family.

The phrase "Java + Kotlin in a single SDK" can mean four meaningfully
different things, so this ADR enumerates all of them rather than treating
it as a binary.

## Options Considered

### Option A — Java only

A single artifact, all Java sources, no Kotlin in the build.

**Pros**

- Maximum JVM reach. Kotlin, Scala, Groovy, and Clojure consumers all use
  Java SDKs comfortably; many Kotlin users *prefer* Java SDKs because they
  don't pull in surprise transitives.
- Smallest dependency footprint: no `kotlin-stdlib` (~1.5 MB) imposed on
  consumers.
- Single language, single compiler, single style guide (Google Java
  Style). Lowest contribution barrier; easiest to maintain and review.
- Aligns directly with the canonical requirements doc, which specifies
  Java idioms (exceptions, `CompletableFuture`, POJOs, camelCase). No
  translation layer needed.
- Mirrors the prevailing pattern among major published Java SDKs: AWS SDK
  for Java v2, Stripe Java, Square OkHttp, Google Cloud Java client
  libraries — all are Java-only.
- Kotlin consumers remain a first-class audience provided we ship
  [JSpecify](https://jspecify.dev/) nullability annotations and follow
  basic Kotlin-interop hygiene (no Kotlin-reserved parameter names,
  SAM-friendly callbacks, getter discipline). This mirrors the
  JavaScript SDK's TypeScript story: one artifact, type metadata makes
  the second-language audience first-class. Detailed requirements live
  in [Java SDK Requirements §2](../java-sdk-requirements.md#2-kotlin-interoperability).

**Cons**

- Kotlin users don't get suspend functions, DSL builders, or sealed-class
  exhaustive `when`. They use `CompletableFuture.await()` from
  `kotlinx-coroutines-jdk8` and slightly more verbose builders.
- More boilerplate in response models (records help, but there is no
  `data class` parity).
- We give up the option to use Kotlin internally even where it would
  shorten implementation code.

### Option B — Mixed sources (Java + Kotlin in one module)

A single published artifact whose source tree contains both Java and
Kotlin files, compiled together.

**Pros**

- Implementation flexibility: Kotlin where it shortens code (data classes
  for models, builder DSLs), Java for the public API.
- Some compile-time conveniences (data classes, when expressions) without
  forcing Kotlin onto callers — the *public* API can still be hand-written
  Java.

**Cons**

- Adds `kotlin-stdlib` as a transitive dependency on every consumer,
  including Java-only consumers who get no benefit from it.
- Two compilers, two style guides, two sets of idioms in one repo.
  Maintenance and review burden roughly doubles for net-new contributors.
- Kotlin metadata baked into the JAR is opaque to some pure-Java tooling
  (older static analyzers, certain IDE inspections, some bytecode
  rewriters).
- Compile times are longer; CI is more complex.
- Almost no major published Java SDK uses this pattern. The lack of
  precedent is itself a signal.

### Option C — Kotlin-first with Java interop (`@JvmStatic`, `@JvmOverloads`)

All sources are Kotlin; Java callers use the result through Kotlin's
Java-interop annotations.

**Pros**

- Kotlin consumers get a fully idiomatic API: coroutines, sealed types,
  named arguments, default arguments, data classes, extension functions.
- Less source code overall when measured against Option A.

**Cons**

- Forces `kotlin-stdlib` on every Java consumer (same cost as Option B).
- Java callers see compilation artifacts in their IDE: synthetic methods,
  `Companion` objects, generated overload sets. Less clean than a
  hand-written Java API.
- Source becomes cluttered with interop annotations: `@JvmStatic`,
  `@JvmOverloads`, `@JvmField`, `@file:JvmName`, and so on, applied
  defensively across the public surface.
- Some Kotlin features (suspend functions, inline reified generics, value
  classes) don't translate cleanly to Java — we'd end up shipping a
  Java-flavored shim layer alongside.
- Conflicts with the canonical requirements doc on async: the doc
  specifies `CompletableFuture` for Java; Kotlin's natural async is
  `suspend`. We'd be writing Kotlin that pretends to be Java.

### Option D — Two separate artifacts (`marketdata-sdk-java` + `marketdata-sdk-kotlin`)

Two artifacts (likely two modules in this repo, or two repos), each
idiomatic for its language.

**Pros**

- Each artifact is clean and fully idiomatic.
- No transitive cost imposed on the other audience.
- Kotlin SDK can use coroutines, sealed types, and DSLs without
  compromise.

**Cons**

- 2× maintenance, 2× tests, 2× documentation, 2× release process — for
  the lifetime of the SDKs.
- Drift risk every time the canonical requirements doc evolves: two
  implementations to keep in sync.
- The current mandate is to ship a Java SDK; a Kotlin SDK is additional
  scope not asked for.
- Doubles the surface area for security review, CVE response, and major
  version migrations.

### Option E (deferrable) — Java-only with optional Kotlin extensions artifact

Not exclusive with Option A; listed for completeness because it's a
common follow-up shape: ship the SDK as Java-only, and optionally
publish a thin `marketdata-sdk-java-kotlin` extensions JAR later. That
extensions artifact would provide suspend wrappers around
`CompletableFuture`, Kotlin-friendly builder DSLs, and idiomatic
collection bridges. Java consumers would never depend on it.

This is the approach used by Spring (`spring-context` + Kotlin
extensions), Reactor, and gRPC.

It is included here only to note that choosing Option A today does not
foreclose Kotlin ergonomics later — it simply defers the cost until
demand is known.

## Claude's Recommendation

**Option A (Java only),** with Option E held in reserve for if Kotlin
demand materializes after launch.

The deciding factors:

1. The canonical requirements doc pins Java idioms explicitly. Options B
   and C both end up translating away from natural Kotlin to comply — at
   which point we have taken on Kotlin's costs (transitive deps, build
   complexity, annotation noise) without getting most of its benefits.
2. Option D doubles maintenance forever for additional scope that wasn't
   asked for.
3. Kotlin/Scala/Groovy/Clojure consumers all use Java SDKs every day.
   The common-case Kotlin user opening a Java SDK has no problem.
4. If demand for an idiomatic Kotlin surface emerges post-launch, Option
   E is a small, additive follow-up that imposes nothing on existing
   Java consumers.

This is a recommendation, not a decision. The team should weigh internal
factors I don't see — existing Kotlin expertise, internal tooling
preferences, customer Kotlin usage signal — before choosing.

## Decision

**Option A — Java only.** The SDK is published as a single Java artifact
with no Kotlin sources and no `kotlin-stdlib` transitive dependency.

Kotlin consumers are treated as a first-class audience served via Java
interop, not via a separate Kotlin-flavored API. The SDK adopts the
Kotlin interoperability requirements in
[Java SDK Requirements §2](../java-sdk-requirements.md#2-kotlin-interoperability):
JSpecify nullability annotations on the entire public surface, avoidance
of Kotlin reserved words in public names, SAM-friendly callbacks, getter
discipline, and a Kotlin example in the README.

Option E (a separate `marketdata-sdk-java-kotlin` extensions artifact
providing suspend wrappers and Kotlin DSL builders) remains a deferred
follow-up. We will revisit it only if Kotlin demand for ergonomics
beyond the interop checklist materializes after launch.

## Consequences

Follow-on work implied by each option. The chosen option is marked.

- **A (chosen):** Build configured for Java only — no Kotlin sources, no
  `kotlin-stdlib` dependency. JSpecify is added as a compile-time
  dependency and applied across the public API. The Kotlin interop
  checklist in
  [Java SDK Requirements §2](../java-sdk-requirements.md#2-kotlin-interoperability)
  becomes part of the Java SDK style guide and acceptance criteria. A
  future Kotlin extensions artifact (Option E) remains an open option but
  is not in current scope.
- **B:** Add Kotlin Gradle plugin; agree on which packages may use Kotlin;
  document the convention so contributors know when each language applies.
- **C:** Entire codebase is Kotlin; Java-interop annotations become part
  of the style guide; we accept the requirements-doc tension on async.
- **D:** Stand up a second module/artifact with its own publishing
  pipeline and CI matrix.

## References

- [Market Data SDK Requirements](../sdk-requirements.md) — canonical
  requirements, especially the language conventions table
- [Python SDK ADR-001](../../../sdk-py/docs/adr/ADR-001-modular-resource-architecture.md)
  — format and tone reference
- [Kotlin Java interop documentation](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2),
  [Stripe Java](https://github.com/stripe/stripe-java),
  [Google Cloud Java](https://github.com/googleapis/google-cloud-java) —
  reference points for major Java-only SDKs
- [Spring Framework Kotlin extensions](https://docs.spring.io/spring-framework/reference/languages/kotlin.html)
  — reference for Option E
