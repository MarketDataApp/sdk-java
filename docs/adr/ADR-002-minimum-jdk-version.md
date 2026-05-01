# ADR-002: Minimum JDK Version

## Status

Proposed — under discussion.

## Context

Once [ADR-001](./ADR-001-java-only-vs-multi-language-sdk.md) settles the
language scope, the next foundational decision is the minimum JDK we
require of consumers. This sets:

- The reach of the SDK (which consumer runtimes can use it)
- The language features available to the implementation (records,
  sealed types, pattern matching, virtual threads)
- The dependency footprint (`java.net.http` is JDK 11+; older targets
  force a third-party HTTP client)
- The long-term maintenance horizon (LTS support windows differ)

We do **not** currently have customer telemetry on which JDK versions
Market Data API customers run. The decision must therefore be made on
principle — what's a reasonable modern target for a new SDK shipped in
2026 — rather than on installed-base data.

A separate but related concept: **build-and-test JDK vs minimum target
JDK**. The two are independent. We can develop and test on JDK 21 while
producing bytecode that runs on JDK 11 by using `javac --release 11`.
This ADR is about the *minimum target*, not the build JDK.

LTS landscape as of May 2026:

| JDK | Released | Premier support | Free LTS via Temurin/Corretto |
|-----|----------|-----------------|-------------------------------|
| 8   | 2014     | Ended (paid only) | ~2030                       |
| 11  | 2018     | Ended (paid only) | ~2027                       |
| 17  | 2021     | Until 2024 (paid extends) | ~2029                 |
| 21  | 2023     | Until 2026      | ~2031                         |
| 25  | 2025-09  | Active          | ~2033                         |

Reference points — what major published Java SDKs target as of 2026:

| SDK                             | Minimum JDK |
|---------------------------------|-------------|
| AWS SDK for Java v2             | 8           |
| Stripe Java                     | 8           |
| Google Cloud Java client libs   | 8           |
| Twilio Java                     | 8           |
| OkHttp 5                        | 8           |
| Spring Boot 3                   | 17          |
| Spring Framework 6/7            | 17          |
| Jackson 3                       | 17          |
| Spring Boot 4 (late 2025)       | 21          |

SDK libraries lean conservative; application frameworks lean modern.

## Options Considered

### Option A — JDK 8

**Pros**

- Maximum consumer reach. Java 8 is still deployed in long-tail
  enterprise environments, and the dominant Java SDKs (AWS, Stripe,
  Google, Twilio) target it precisely for this reason.
- Free OpenJDK builds available through ~2030 via Eclipse Temurin and
  Amazon Corretto.

**Cons**

- `java.net.http.HttpClient` requires JDK 11+. Targeting 8 forces us
  onto a third-party HTTP client (OkHttp, Apache HttpClient), which
  contradicts the zero-runtime-dependency direction implied by the
  requirements doc.
- No `var`, no records, no sealed types, no pattern matching, no text
  blocks. Significantly more boilerplate, especially in the typed
  response models the requirements doc requires (§11.2).
- Less ergonomic exception hierarchy: no sealed types means the base
  `MarketDataException` cannot statically enumerate its subtypes.
- Locks the SDK to 2014-era language idioms for its lifetime.

### Option B — JDK 11

**Pros**

- `java.net.http.HttpClient` available — no third-party HTTP dependency.
- `var` for cleaner local-variable code.
- Still very widely deployed; covers most non-modern enterprise users.
- Free OpenJDK support runway through ~2027.

**Cons**

- No records. Every response POJO is hand-written boilerplate
  (`equals`, `hashCode`, `toString`, getters) — significant code volume
  in an SDK whose surface is mostly typed response models.
- No sealed types. The exception hierarchy must use abstract base
  classes, and consumers cannot rely on exhaustive `switch` over error
  types.
- No `instanceof` pattern matching, no text blocks. Implementation code
  is wordier.
- The "modern Java" baseline has shifted: Spring Boot 3 (the dominant
  application framework) raised its minimum to 17 in 2022. Targeting
  11 in 2026 reads as conservative for a new project.

### Option C — JDK 17

**Pros**

- **Records** — natural fit for the SDK's many typed response models;
  removes the largest single source of boilerplate.
- **Sealed types** — a sealed `MarketDataException` hierarchy lets
  consumers exhaustively handle error types and the compiler enforces
  it.
- `instanceof` pattern matching, text blocks, switch expressions — all
  reduce implementation noise.
- Current mainstream baseline for new Java libraries shipped in 2024+.
- Free OpenJDK runway through ~2029.
- `java.net.http.HttpClient` and `var` of course included.

**Cons**

- Excludes consumers still on JDK 8 or 11. Without telemetry we cannot
  quantify how large that population is among Market Data API users.
- Some long-tail enterprise environments may not be able to upgrade
  quickly.

### Option D — JDK 21

**Pros**

- **Virtual threads** — the headline feature of modern Java. Critical
  caveat below.
- Switch patterns and record patterns (much nicer response handling).
- Sequenced collections.
- Free OpenJDK runway through ~2031.

**Cons**

- **Virtual threads benefit our consumers more than they benefit us.**
  A consumer running their HTTP server with virtual-thread-per-request
  can call our sync SDK methods and get the scaling benefits even if
  our SDK is compiled for 17 — virtual threads are a runtime property,
  not a compile target. The argument "we should be on 21 because of
  virtual threads" is largely a misconception for an SDK library.
- Consumer install base is materially smaller than 17. Without
  telemetry we cannot weigh this confidently.
- The features that *do* require a 21 compile target (switch patterns,
  record patterns) are quality-of-life improvements, not capability
  unlocks.

### Option E — JDK 25

**Pros**

- Newest LTS; longest support runway (~2033).
- Stable structured concurrency, scoped values.
- Latest pattern matching refinements.

**Cons**

- Released September 2025; consumer adoption is very thin in May 2026.
- Targeting 25 today is functionally a beta-tester pact with consumers.
- All 25-only features are nice-to-haves; nothing about this SDK
  *requires* them.

### Option F (cross-cutting) — Multi-Release JAR

Not exclusive with options A–E; listed because it's a real alternative
that changes the tradeoff.

A multi-release JAR ships a baseline implementation (e.g. JDK 8 or 11)
plus version-specific overrides under `META-INF/versions/{N}/`. Consumers
on JDK 11 see the JDK 11 classes; consumers on JDK 17 see the JDK 17
overrides automatically. AWS SDK for Java v2 uses this pattern.

**Pros**

- Maximum reach **and** modern features. JDK 8 consumers get a working
  SDK; JDK 17+ consumers get records, sealed types, etc. in the parts
  of the codebase that have overrides.
- No forced upgrade for older consumers.

**Cons**

- Real build complexity: separate source sets per JDK target, multi-stage
  Gradle/Maven configuration, careful management of which classes go in
  which version directory.
- CI must run the full test suite against every supported JDK target.
- Bytecode debugging is harder when the runtime class differs from the
  source-of-truth class.
- Easy to introduce subtle behavior differences between version
  overrides.
- Significant ongoing maintenance burden — every internal change must
  be considered against every shipped JDK level.
- Generally only worth it for SDKs with **known** large old-runtime
  install bases. Without telemetry we can't justify the cost.

## Claude's Recommendation

**JDK 17.**

The deciding factors:

1. The SDK's surface is dominated by typed input/response models.
   Records cut the boilerplate cost on those models substantially, and
   that benefit recurs every time a new endpoint is added.
2. Sealed types make the required exception taxonomy (requirements §6)
   cleaner and exhaustive at the compiler level.
3. JDK 17 has been the default minimum for new modern-Java libraries
   since Spring Boot 3 raised its baseline in 2022. Choosing 17 in
   2026 is a conservative-by-now choice, not an aggressive one.
4. The strongest case for 21 (virtual threads) is largely a misread of
   how virtual threads work — they benefit consumers regardless of our
   compile target.
5. Without customer telemetry, the strongest case for 11 ("users might
   be stuck there") is unsupported. We cannot weight an unknown
   population.
6. Multi-release JAR could in principle give us reach + features, but
   the build and maintenance complexity is hard to justify without
   evidence that the consumer base actually needs it.

The strongest reasonable counter-recommendation is **JDK 11**, on the
basis that an SDK should err toward maximum reach when in doubt. That's
a defensible position; this ADR documents it for the team to weigh.

## Decision

*To be filled in by the team.*

## Consequences

*To be filled in once a decision is made. Notable downstream effects:*

- **A (JDK 8):** add an HTTP client dependency (OkHttp or Apache);
  abandon plans for records-based response models and sealed exception
  hierarchy.
- **B (JDK 11):** keep `java.net.http.HttpClient`; hand-write all
  POJOs; abstract-base exception hierarchy instead of sealed.
- **C (JDK 17):** records for all response models; sealed exception
  hierarchy; pattern matching in implementation. This shapes most
  later ADRs (response model design, exception design).
- **D (JDK 21):** as C, plus switch/record patterns; smaller consumer
  pool to launch into.
- **E (JDK 25):** as D, with even smaller consumer pool.
- **F (multi-release JAR):** establishes a separate source set per
  target JDK; CI matrix expands; every change considered across all
  shipped levels.

## References

- [Market Data SDK Requirements](../sdk-requirements.md)
- [JDK release schedule](https://www.java.com/releases/) and
  [Eclipse Temurin support roadmap](https://adoptium.net/support/)
- [JEP 395: Records](https://openjdk.org/jeps/395)
- [JEP 409: Sealed Classes](https://openjdk.org/jeps/409)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [AWS SDK for Java v2 — multi-release JAR usage](https://github.com/aws/aws-sdk-java-v2)
- [Spring Boot 3 system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [ADR-001 — Java-Only vs Multi-Language SDK](./ADR-001-java-only-vs-multi-language-sdk.md)
