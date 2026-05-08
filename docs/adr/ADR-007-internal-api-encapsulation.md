# ADR-007: Encapsulation of Internal Types on the Public API Surface

## Status

Proposed.

## Context

Resource façades like `MarketsResource` are part of the public API
(`com.marketdata.sdk.markets`) — consumers reach them via
`client.markets()`. They are also constructed by
`MarketDataClient`, which lives in a *different* package
(`com.marketdata.sdk`). Java's visibility levels are `private`,
package-private, `protected`, and `public`; there is no
"module-internal cross-package" level in the language base.
Cross-package construction therefore requires the constructor to be
`public`, even when conceptually "this should not be called from
outside the SDK".

The current `MarketsResource(HttpTransport transport)` constructor
illustrates the problem:

```java
// src/main/java/com/marketdata/sdk/markets/MarketsResource.java
/**
 * Package-private: only {@link com.marketdata.sdk.MarketDataClient} constructs
 * resources, so consumers get one via {@code client.markets()}.
 */
public MarketsResource(HttpTransport transport) {  // ← Javadoc lies; ctor is public
    this.transport = transport;
}
```

`HttpTransport` lives in `com.marketdata.sdk.internal.http`. The
package is named `internal` *by convention* — the language gives no
enforcement. Direct consequences:

- A consumer can write
  `new MarketsResource(new HttpTransport("https://...", "v1", "ua", "token"))`
  and bypass `MarketDataClient` and everything it enforces (configuration
  cascade per §4, demo mode, future startup validation, retry
  policy, telemetry).
- Generated Javadoc lists the constructor as part of the public API,
  blurring the contract.
- Any change to `HttpTransport`'s constructor signature is a
  technically SemVer-breaking change because that signature appears
  in a public-API position — even though the type *lives in* a package
  named `internal`. SemVer-strict tooling (`revapi`, `japicmp`) will
  flag it.

The leak is the same kind every non-modular Java SDK has (Stripe Java,
AWS SDK v1, Twilio). It is moderate severity rather than critical, but
it directly contradicts the Kotlin-interop and clean-API goals of
ADRs 001 and 006.

A related observation: the same problem appears for *every* resource
that lands in subsequent endpoint work (`stocks`, `options`, `funds`,
`utilities`). Whatever we choose, it must scale to roughly five resource
façades by v1.

## Options Considered

### Option A — JPMS (`module-info.java`)

Add a Java module descriptor at `src/main/java/module-info.java` that
exports only the public-API packages and leaves `internal.*` unexported.
Consumers using the modulepath will find `HttpTransport` literally
unreachable: the import line will fail to compile.

```java
module com.marketdata.sdk {
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires static org.jspecify;            // compile-time only

    exports com.marketdata.sdk;
    exports com.marketdata.sdk.markets;
    exports com.marketdata.sdk.exception;
    // com.marketdata.sdk.internal.*  — intentionally NOT exported
}
```

**Pros**

- Zero refactor of existing source. The current package layout is
  preserved verbatim — `internal/http`, `internal/wire/markets`, the
  whole tree stays where it is.
- Compile-time enforcement at the *language* level for modulepath
  consumers: the consumer's compiler refuses
  `import com.marketdata.sdk.internal.http.HttpTransport`.
- IDEs surface the unexported package as a warning or error even when
  the consumer is on classpath.
- SemVer-strict tooling (`revapi`, `japicmp`) reads `module-info` and
  knows precisely what the API surface is; spurious "breaking
  changes" on `internal.*` types disappear.
- This is what AWS SDK v2, Spring Boot (modular), Hibernate ORM
  (modular) do.

**Cons**

- Enforcement is full only on **modulepath** consumers. Classpath
  consumers see `internal.*` as part of the unnamed module and can
  still reference it (with IDE warnings).
- Adds module-system reasoning to the build (transitive `requires`,
  test patching). Gradle 9 + `java-library` handles this transparently
  for our case, but it is one more thing to know.
- Reflection still works the same way as today: callers using
  `setAccessible(true)` can reach private members. To block that, the
  consumer would need to be denied `--add-opens` for our packages —
  which is the JVM default for non-exported packages.

#### End-user impact (special focus per the request)

How JPMS affects each consumer cohort:

- **Production consumer using Maven/Gradle classpath (most common today):**
  Library appears on the classpath, the JVM treats it as part of the
  unnamed module, and `exports` directives are *not enforced at runtime*.
  Such a consumer can still write
  `import com.marketdata.sdk.internal.http.HttpTransport;`. **However**,
  IntelliJ, Eclipse, and recent VS Code Java extensions display a
  visible warning ("module com.marketdata.sdk does not export
  com.marketdata.sdk.internal.http") — strong social signal, no hard
  block.

- **Production consumer using modulepath (`requires com.marketdata.sdk;`
  in their own `module-info.java`):**
  The import simply does not compile. The consumer's build fails with
  "package com.marketdata.sdk.internal.http is declared in module
  com.marketdata.sdk, which does not export it." This is the cohort the
  ADR primarily protects.

- **Consumer running tests against the SDK:**
  Their test code, when it lives in their own module, is governed by
  the same rules as their production code. Tests in our SDK's own
  module continue to access internals via the test source set
  (Gradle handles `--patch-module` for test classpaths automatically).

- **Reflective consumer (advanced):**
  Default behaviour for non-exported packages is "deep reflection
  blocked." The consumer must add `--add-opens
  com.marketdata.sdk/com.marketdata.sdk.internal.http=ALL-UNNAMED` to
  the JVM args of their application — a deliberate choice that is now
  visible at deploy time, not buried in code.

- **Consumer of the published Javadoc:**
  Standard Javadoc for a modular JAR omits the unexported packages.
  `com.marketdata.sdk.internal.*` no longer appears in the rendered
  docs, eliminating the "Javadoc lists the constructor as public API"
  symptom.

- **Consumer running a SemVer-comparison tool (`revapi`, `japicmp`):**
  Tools that read `module-info` correctly classify `internal.*` as
  non-API. Changes to `HttpTransport`'s signature stop registering as
  breaking — they were never API.

- **Migration cost on the consumer side:**
  Zero for classpath consumers (they keep working as before). Modulepath
  consumers add `requires com.marketdata.sdk;` to their `module-info` —
  a one-line change, standard for any modular dependency.

The asterisk to be honest about: a determined classpath consumer can
*technically* still use `internal.*` types — but they get IDE
warnings, lose Javadoc visibility, and accept that any future change
to those types is by definition not a SemVer break (we can change them
freely). The contract is now legible.

### Option B — Single-package infra (collapse the directory tree)

Move `MarketsResource` (and every future resource façade), `HttpTransport`,
`AsyncSemaphore`, `RequestSpec`, `HttpStatusMapper`, `RateLimitHeaders`,
`Configuration`, `EnvVars`, `Tokens`, `Version`, plus every wire-format
deserializer — all into the same package as `MarketDataClient`
(`com.marketdata.sdk`). Drop the `public` modifier from every
"internal" class so it becomes package-private. The compiler then
refuses to compile a consumer's reference to those types because the
type itself is not visible.

```
com.marketdata.sdk/
├── MarketDataClient.java                    public
├── RateLimits.java                          public
├── MarketsResource.java                     public final, ctor package-private
├── HttpTransport.java                       package-private
├── AsyncSemaphore.java                      package-private
├── RequestSpec.java                         package-private
├── HttpStatusMapper.java                    package-private
├── RateLimitHeaders.java                    package-private
├── Configuration.java                       package-private
├── MarketStatusDeserializer.java            package-private
├── SdkJsonModule.java                       package-private
└── ... ~25 files in a single root package

com.marketdata.sdk.markets/                  ← only public response DTOs
├── MarketStatus.java
└── DailyStatus.java

com.marketdata.sdk.exception/                ← only public exceptions
└── ...
```

The consumer cannot import a type that is not `public`, so:

```java
// Does NOT compile:
import com.marketdata.sdk.HttpTransport;       // package-private
new MarketsResource(/*...*/);                  // package-private ctor
```

**Pros**

- Pure Java visibility. No JPMS, no module reasoning, no tooling
  asterisk. Compile-time enforcement everywhere — classpath and
  modulepath alike.
- Reflection still gated by `setAccessible(true)` + `--add-opens` in
  modular runtimes; for non-modular runtimes the bar is the same as
  the rest of the JDK.
- Industry precedent: Stripe Java's pre-modular layout uses a similar
  structure (one root package, response DTOs in subpackages).

**Cons**

- Refactor scope is real: ~25 files change package + the `internal/`
  subdirectory disappears as a visual cue (replaced by the
  `package-private` modifier as the "do not touch" signal).
- The `@JsonDeserialize(using = MarketStatusDeserializer.class)`
  annotation on response records can no longer reference a
  package-private class from another package. Deserializers must move
  to programmatic registration via a `SimpleModule` on
  `HttpTransport`'s `ObjectMapper`. This is a real architectural change
  on top of the move (decoupling response models from their wire-format
  logic — arguably an improvement, but additional work).
- Test sources for any package-private type must live in the matching
  test package, which already happens — but every test that previously
  imported `com.marketdata.sdk.internal.http.HttpTransport` now imports
  `com.marketdata.sdk.HttpTransport`. Mechanical, not zero.
- Naming feels slightly off: `com.marketdata.sdk.MarketsResource` instead
  of `com.marketdata.sdk.markets.MarketsResource`. Consumers rarely
  import the resource class by name (they go through
  `client.markets()`), but it is a smell at first glance.
- A single package with ~25 classes is fine technically but
  organisationally noisier than the current layered tree.

## Claude's Recommendation

**Option A (JPMS), with Option B as a follow-on if and when the team
decides classpath enforcement is required.**

Reasoning:

1. JPMS is the **lowest-risk** option that produces real enforcement
   for the most relevant consumer cohorts. ~1 file added (`module-info.java`),
   no source moves, no API renames, no annotation refactor.
2. Modulepath adoption among Java consumers is increasing every year.
   AWS SDK v2 and Spring Boot have already crossed the bar; Java
   consumers building anything modular today already write
   `module-info` files. By the time this SDK has been in the field for
   v1.x lifetime, modulepath enforcement will cover the majority of
   serious consumers.
3. The classpath asterisk is real but mitigated by IDE warnings and
   SemVer tooling that already reads `module-info` correctly. The
   consumer who *deliberately* imports `internal.*` from classpath has
   accepted a one-sided contract — we can change those types freely.
4. Option B genuinely closes the gap for classpath consumers but at
   substantial refactor cost (~25 file moves + deserializer
   registration refactor). Worthwhile only if the threat model is
   "adversarial classpath consumer". For "respectful classpath consumer
   + adversarial modulepath consumer", Option A is sufficient and B is
   over-engineering.

The strongest counter-argument to Option A is "it is not enforcement,
only a strong signal, for classpath consumers." That is correct. The
counter-counter-argument is "every Java SDK in our space has the same
property; the alternative (Option B) costs more than the gap is
worth." If the team disagrees on the threat model, Option B is the
escape hatch — but it should be a deliberate choice, not a default.

## Decision

*Pending team review.*

## Consequences

Follow-on work implied by each option. The chosen option will be
marked when the decision is made.

- **A (JPMS, recommended):** Add `src/main/java/module-info.java` with
  the exports listed in the option above. Add `requires` clauses for
  Jackson and JSpecify (`requires static org.jspecify` for compile-time
  only). Update CLAUDE.md to record the modulepath/classpath
  enforcement contract. No source moves, no refactor of resource code.
  Tests continue to access internals via Gradle's standard test source
  set wiring. Verify that `./gradlew build` still succeeds end-to-end
  and that `javadoc` no longer surfaces `internal.*` packages.

- **B (single-package infra):** Move every infra and resource façade
  class to `com.marketdata.sdk`; drop `public` from every type that
  was internal. Refactor `@JsonDeserialize` annotations off response
  records and into a programmatic `SimpleModule` registered on
  `HttpTransport`'s `ObjectMapper`. Update every test that imports
  formerly-internal types to the new package. Update CLAUDE.md to
  describe the single-package convention and the package-private
  default for non-DTO classes.

## References

- [Java Language Specification §7 — Packages](https://docs.oracle.com/javase/specs/jls/se17/html/jls-7.html)
- [JEP 261: Module System (JPMS)](https://openjdk.org/jeps/261)
- [State of the Module System — Mark Reinhold](https://openjdk.org/projects/jigsaw/spec/sotms/)
- [Stripe Java SDK — package layout](https://github.com/stripe/stripe-java)
- [AWS SDK for Java v2 — modular layout](https://github.com/aws/aws-sdk-java-v2)
- [`revapi` — API change tracking with module-info awareness](https://revapi.org/)
- [ADR-001 — Java Only vs. Multi-Language SDK](./ADR-001-java-only-vs-multi-language-sdk.md)
- [ADR-002 — Minimum JDK Version](./ADR-002-minimum-jdk-version.md)
