# Java SDK Requirements

Java-specific requirements for the Market Data Java SDK. These derive
from architectural decisions recorded in [ADRs](adr/) and **supplement,
not replace,** the cross-language
[Market Data SDK Requirements](sdk-requirements.md). When this document
conflicts with the generic requirements doc, this document wins for the
Java SDK only.

Each requirement section cites the ADR it derives from. New requirements
should be added here only after the corresponding ADR is accepted.

## Source ADRs

| Section                       | Source ADR                                                         |
|-------------------------------|--------------------------------------------------------------------|
| §1 Distribution               | [ADR-001](adr/ADR-001-java-only-vs-multi-language-sdk.md)          |
| §2 Kotlin Interoperability    | [ADR-001](adr/ADR-001-java-only-vs-multi-language-sdk.md)          |
| §3 JDK Targets                | [ADR-002](adr/ADR-002-minimum-jdk-version.md)                      |
| §4 HTTP Client                | [ADR-004](adr/ADR-004-http-client.md)                              |
| §5 Build Tool                 | [ADR-003](adr/ADR-003-build-tool.md)                               |
| §6 JSON Library               | [ADR-005](adr/ADR-005-json-library.md)                             |
| §7 Async API Surface          | [ADR-006](adr/ADR-006-async-api-surface.md)                        |

---

## 1. Distribution

Source: [ADR-001](adr/ADR-001-java-only-vs-multi-language-sdk.md)

- The SDK is published as a single Java artifact.
- Sources are Java only — no Kotlin sources in the build.
- The published JAR must not bring `kotlin-stdlib` as a transitive
  dependency. JSpecify annotations are compile-time only and do not
  count.
- A separate Kotlin extensions artifact (Option E in ADR-001) is out of
  scope for the initial release. Revisit only if Kotlin demand for
  ergonomics beyond the interop checklist materializes after launch.

---

## 2. Kotlin Interoperability

Source: [ADR-001](adr/ADR-001-java-only-vs-multi-language-sdk.md)

The SDK is published as Java only, but Kotlin consumers are a
first-class audience served via Java interop. This mirrors the
JavaScript SDK's TypeScript story: one artifact, with type metadata that
makes the second-language audience first-class.

The following requirements apply to the entire public API surface.

### 2.1 Nullability Annotations (Required)

- Annotate every public type, parameter, return, and field with
  [JSpecify](https://jspecify.dev/) nullability annotations.
- Apply `@NullMarked` at the package level (via `package-info.java`) so
  non-null is the default; mark nullable items explicitly with
  `@Nullable`.
- Without these annotations, Kotlin sees Java values as platform types
  (`String!`) and Kotlin's null safety silently breaks.

### 2.2 Avoid Kotlin Reserved Words

Do not use any of the following as public method or parameter names —
they force Kotlin callers to wrap calls in backticks:

`object`, `is`, `in`, `fun`, `when`, `as`, `val`, `var`, `typealias`,
`interface`, `package`, `typeof`, `out`, `super`

### 2.3 Property-Style Getter Discipline

Java getters become Kotlin properties at the call site: `getFoo()` is
callable as `.foo`, `isFoo()` as `.foo`. Therefore:

- Do not perform expensive work, network/disk I/O, or anything with
  observable side effects in a getter — Kotlin users will treat it as a
  field read.
- Use consistent getter naming (`getFoo` for objects, `isFoo` for
  booleans) so Kotlin sees a clean property name.

### 2.4 SAM-Friendly Callbacks

Single-abstract-method (SAM) Java interfaces auto-convert to Kotlin
lambdas. For any callback or listener interface in the public API:

- Keep it to a single abstract method (no `default` second method).
- Prefer `java.util.function.*` types (`Consumer`, `Function`,
  `Predicate`, `Supplier`) where applicable — Kotlin treats these as
  lambda-compatible out of the box.

### 2.5 Generic Wildcards

- Use `? extends T` on producer parameters and `? super T` on consumer
  parameters in public APIs.
- Missing wildcards translate to invariant Kotlin types and produce
  awkward call sites.

### 2.6 Collections and Optionals

- Return standard JVM collection types: `List<T>`, `Map<K,V>`, `Set<T>`.
  Do not return arrays for variable-length results.
- Return empty collections, never `null`.
- Do not use `Optional<T>` as a field type or parameter type.
  `Optional<T>` is acceptable only as a return type on Java-facing
  methods; Kotlin callers prefer nullable returns.

### 2.7 Async

- Public async methods return `CompletableFuture<T>` (per the generic
  requirements doc, §11.2).
- Do not depend on `kotlinx-coroutines` from the SDK. Kotlin consumers
  bridge via `CompletableFuture.await()` from `kotlinx-coroutines-jdk8`.

### 2.8 Documentation

The README and per-method docs must include at least one Kotlin usage
example alongside the Java example for the quick-start path:

```kotlin
val client = MarketDataClient.builder()
    .apiKey("KEY")
    .build()

val quote = client.stocks().quote("AAPL")
println(quote)
```

---

## 3. JDK Targets

Source: [ADR-002](adr/ADR-002-minimum-jdk-version.md)

- **Minimum runtime:** JDK 17 (LTS). Consumers must run JDK 17 or
  newer.
- **Build target:** the published artifact is compiled with
  `javac --release 17` so its bytecode runs on any JDK 17+ runtime.
- **CI test matrix:** unit tests run on JDK 17, 21, and 25 to catch
  forward-compat regressions. Mirrors the multi-version testing
  approach used in the Python SDK.
- **No multi-release JAR.** Single bytecode level for the entire
  artifact.
- **Minimum supported JDK** must be documented in the README.

---

## 4. HTTP Client

Source: [ADR-004](adr/ADR-004-http-client.md)

- The SDK uses `java.net.http.HttpClient` from the JDK standard library
  exclusively.
- **No third-party HTTP client** (OkHttp, Apache HttpClient, etc.) may
  be added as a runtime dependency.
- HTTP/2 must be enabled (it is the default for `java.net.http`).
- A single shared `HttpClient` instance per `MarketDataClient` provides
  automatic connection pooling (per generic SDK requirements doc §1.1).
- Timeouts must satisfy the generic SDK requirements doc §10
  (99-second request timeout, 2-second connect timeout).
- Async methods return `CompletableFuture<T>` directly from
  `HttpClient.sendAsync` — no adapter layer.

---

## 5. Build Tool

Source: [ADR-003](adr/ADR-003-build-tool.md)

- The SDK is built with **Gradle** using the **Kotlin DSL**
  (`build.gradle.kts`, `settings.gradle.kts`).
- Dependency versions are managed via a Gradle version catalog
  (`gradle/libs.versions.toml`).
- Standard plugins: `java-library`, `maven-publish`, [Vanniktech Maven
  Publish](https://github.com/vanniktech/gradle-maven-publish-plugin)
  (or Gradle Nexus Publish) for Maven Central,
  [Spotless](https://github.com/diffplug/spotless) for formatting,
  [JaCoCo](https://www.jacoco.org/) for coverage.
- Integration tests live in a separate Gradle source set
  (`integrationTest`), gated by environment variable per generic SDK
  requirements doc §13.

---

## 6. JSON Library

Source: [ADR-005](adr/ADR-005-json-library.md)

- The SDK uses **Jackson** (`jackson-databind`) as its JSON library.
- Records-based response models use Jackson's record support
  (Jackson 2.12+). Records are the default model shape (per §3 — JDK
  17 minimum).
- The API's compressed parallel-arrays wire format (generic SDK
  requirements doc §11.1) is decoded via custom Jackson
  `JsonDeserializer` classes.
- The minimum Jackson version is documented in the published POM and
  README.
- The Jackson dependency is **not shaded** in v1. Shading is held in
  reserve as a mitigation if classpath collisions become a real
  customer pain post-launch.

---

## 7. Async API Surface

Source: [ADR-006](adr/ADR-006-async-api-surface.md)

- Every public endpoint method exposes both a **sync** and an
  **async** variant:

  ```java
  Quote quote = client.stocks().quote("AAPL");
  CompletableFuture<Quote> quoteFuture = client.stocks().quoteAsync("AAPL");
  ```

- Async method names use the `<methodName>Async` convention
  consistently.
- Async methods return `CompletableFuture<T>` (matching the generic
  SDK requirements doc's Java async pattern).
- **Internal logic is async-first.** Sync methods are thin wrappers
  that call `.join()` on the async path and unwrap
  `CompletionException` to surface the underlying cause directly.
- Both surfaces share the same validation, retry, rate-limit, and
  concurrency-pool logic — no parallel implementations.
- Test coverage must include **both sync and async variants** for
  every endpoint.

---

## Acceptance Checklist

### Distribution (§1)
- [ ] No Kotlin sources in the build
- [ ] Published JAR has no `kotlin-stdlib` transitive dependency

### Kotlin Interoperability (§2)
- [ ] JSpecify nullability annotations applied to entire public API
      (`@NullMarked` package-level + `@Nullable` where applicable)
- [ ] No Kotlin reserved words in public method or parameter names
- [ ] Public callback/listener interfaces are SAM (single abstract method)
- [ ] No expensive work or I/O in property-style getters
- [ ] No `Optional<T>` in fields or parameters
- [ ] Generic wildcards correct on public APIs (`? extends` / `? super`
      where appropriate)
- [ ] Kotlin usage example in README quick-start

### JDK Targets (§3)
- [ ] Build pipeline uses `javac --release 17`
- [ ] CI runs unit tests on JDK 17, 21, and 25
- [ ] Minimum supported JDK documented in README
- [ ] Published artifact is not a multi-release JAR

### HTTP Client (§4)
- [ ] All HTTP requests go through `java.net.http.HttpClient`
- [ ] No third-party HTTP client on the dependency tree
- [ ] Single shared `HttpClient` instance per `MarketDataClient`
- [ ] HTTP/2 enabled
- [ ] 99-second request timeout / 2-second connect timeout configured
- [ ] Async methods return `CompletableFuture<T>` natively

### Build Tool (§5)
- [ ] Build uses Gradle with the Kotlin DSL (`.gradle.kts`)
- [ ] Dependency versions managed via `gradle/libs.versions.toml`
- [ ] Integration tests live in a separate `integrationTest` source set
- [ ] Standard plugins applied: `java-library`, `maven-publish`,
      Maven Central publish plugin, Spotless, JaCoCo

### JSON Library (§6)
- [ ] Jackson is the only JSON dependency on the dependency tree
- [ ] Wire-format decoders implemented as custom `JsonDeserializer`
      classes
- [ ] Records-based response models use Jackson's record support
- [ ] Minimum Jackson version documented in POM and README
- [ ] Jackson is not shaded into the SDK JAR (v1)

### Async API Surface (§7)
- [ ] Every endpoint method has both sync and `<methodName>Async`
      variants
- [ ] Async methods return `CompletableFuture<T>`
- [ ] Internal logic is async-first; sync methods wrap `.join()` and
      unwrap `CompletionException`
- [ ] Both surfaces share validation, retry, rate-limit, and
      concurrency-pool logic
- [ ] Tests cover both sync and async paths for every endpoint
