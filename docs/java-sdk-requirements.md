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
