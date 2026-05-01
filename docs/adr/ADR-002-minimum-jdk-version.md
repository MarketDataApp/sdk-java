# ADR-002: Minimum JDK Version

## Status

Proposed — under discussion.

## Context

The minimum JDK we require of consumers determines:

- The reach of the SDK (which consumer runtimes can use it)
- The language features available to the implementation (records,
  sealed types, pattern matching)
- The HTTP client dependency footprint
- The long-term maintenance horizon (LTS support windows differ)

The team has narrowed the active options to **JDK 11** and **JDK 17**.

**JDK 8 is excluded** because `java.net.http.HttpClient` is JDK 11+. We
do not want to ship a third-party HTTP client (OkHttp, Apache
HttpClient) as a runtime dependency — that conflicts with the SDK's
zero-runtime-dependency direction and adds CVE surface that has to be
tracked for the SDK's lifetime. (The dominant Java SDKs that target JDK
8 — AWS, Stripe, Google, Twilio — all carry a third-party HTTP client
for exactly this reason. We're choosing to skip that cost.)

**JDK 21 and 25 are out of scope** for this decision. Both LTS releases
add nice-to-have features (virtual threads, record patterns, scoped
values) but no capability we *need*. Their consumer install base in May
2026 is materially smaller than 17. They can be revisited later if the
case becomes compelling.

We do not currently have customer telemetry on which JDK versions Market
Data API customers run. The decision must therefore be made on principle
— what's a reasonable modern target for a new SDK shipped in 2026 —
rather than on installed-base data.

A separate but related concept: **build-and-test JDK vs minimum target
JDK**. The two are independent. We can develop and test on JDK 21 while
producing bytecode that runs on JDK 11 by using `javac --release 11`.
This ADR is about the *minimum target*, not the build JDK.

LTS landscape as of May 2026 (relevant rows only):

| JDK | Released | Premier (Oracle)  | Free LTS via Temurin / Corretto |
|-----|----------|-------------------|---------------------------------|
| 11  | 2018     | Ended (paid only) | ~2027                           |
| 17  | 2021     | Ended (paid only) | ~2029                           |

Reference points — 2026 minimum-JDK targets in widely used Java
libraries:

| Library                       | Minimum JDK | Notes                          |
|-------------------------------|-------------|--------------------------------|
| AWS SDK for Java v2           | 8           | Ships HTTP client dep          |
| Stripe Java                   | 8           | Ships HTTP client dep          |
| Google Cloud Java client libs | 8           | Ships HTTP client dep          |
| OkHttp 5                      | 8           | Library, not an end-user SDK   |
| Spring Boot 3                 | 17          | Application framework          |
| Spring Framework 6 / 7        | 17          | Application framework          |
| Jackson 3                     | 17          | Library                        |
| Spring Boot 4 (late 2025)     | 21          | Application framework          |

Pattern: SDK libraries that prioritize maximum reach pay the cost of an
external HTTP client and stay on 8. Modern frameworks — and increasingly
modern libraries — have moved to 17.

## Options Considered

### Option A — JDK 11

JDK 11 is the oldest target consistent with the no-HTTP-dependency
constraint. It captures essentially every JVM consumer not running
end-of-life Java.

**Pros**

- `java.net.http.HttpClient` available — no third-party HTTP dependency.
- `var` for cleaner local-variable code in the implementation.
- Wide consumer reach. JDK 11 install base remains substantial in 2026,
  particularly in financial services, government, and large enterprises
  where runtime upgrade cycles run 3–5 years.
- For a market-data SDK in particular, financial-services consumers are
  a likely-large slice of users, and that slice skews conservative on
  JDK upgrades.
- Consumers stuck on JDK 11 today are typically running Spring Boot 2.x
  or older app stacks; an SDK that requires 17 effectively blocks them
  until they finish a framework migration that may already be a
  multi-quarter project.
- Free OpenJDK support runway through ~2027 via Eclipse Temurin and
  Amazon Corretto.

**Cons**

- **No records.** Every response model is a hand-written POJO with
  constructor, getters, `equals`, `hashCode`, and `toString`. Across the
  ~20 typed response models implied by the API surface, that's a
  meaningful and recurring code-volume cost — every new endpoint adds
  the same boilerplate. Workarounds (Lombok `@Value`, AutoValue,
  Immutables) all introduce either a runtime dependency or an
  annotation-processor build step, which we're explicitly trying to
  avoid.
- **No sealed types.** The required exception taxonomy
  (`AuthenticationError`, `RateLimitError`, …) must use an abstract
  base class. Consumers cannot `switch` exhaustively over error types,
  so the compiler won't tell them when we add a new error type in a
  future major version — a real refactoring safety net we'd be giving
  up.
- **No JDK 14–17 syntactic improvements.** No `instanceof` pattern
  matching, no text blocks, no switch expressions. Implementation code
  and test fixtures are wordier across the board.
- **The "modern Java" baseline has shifted.** Spring Boot 3 (Nov 2022),
  Spring Framework 6, Jackson 3, Hibernate 7, Micronaut 4, and Quarkus 3
  all require JDK 17. Targeting 11 in 2026 reads as conservative for a
  new project — we'd be shipping below the baseline of the frameworks
  many of our consumers already use.
- **Shorter free-LTS runway.** Temurin / Corretto free updates for JDK
  11 end around October 2027 — roughly 12–18 months after a 2026 launch.
  Consumers who care about a supported runtime will be pushed off 11
  within the SDK's first major-version cycle, at which point they'll
  upgrade past 11 anyway.

### Option B — JDK 17

JDK 17 is the modern-Java baseline as of 2026. Its features map
unusually well onto an SDK whose surface is dominated by typed models
and a structured exception hierarchy.

**Pros**

- **Records.** A record collapses ~30 lines of POJO boilerplate into a
  single line per response model. Multiplied across ~20 models that's
  on the order of 500+ lines of mechanical code we don't write, don't
  review, and don't maintain. The benefit recurs every time a new
  endpoint or model is added.
- **Sealed types.** A sealed `MarketDataException` hierarchy lets the
  compiler enforce exhaustive handling at consumer call sites. When we
  add an 8th error subtype in a future major version, every consumer's
  `switch` fails to compile until they update — a real refactoring
  safety net, not just a stylistic one.
- **Pattern matching, text blocks, and switch expressions** make
  implementation code, test fixtures, and inline JSON markedly less
  verbose. None of these are individually decisive; together they add up.
- **Aligns with the modern-Java baseline.** Matches Spring Boot 3,
  Jackson 3, and the bulk of the modern Java library ecosystem. New
  consumers picking up a fresh Java SDK in 2026 are most likely already
  on 17+.
- **Longer free-LTS runway.** Temurin / Corretto free updates for JDK 17
  run through ~2029 — about three years after a 2026 launch.
- **`java.net.http.HttpClient` and `var`** are of course included.

**Cons**

- **Excludes consumers still on JDK 11 (or older).** Without telemetry
  we cannot quantify how large that population is among Market Data API
  users. The risk is real but unmeasured.
- **Long-tail enterprise environments may upgrade slowly.** Financial
  services, regulated industries, and some government users ship on
  multi-year JDK refresh cycles. A non-trivial subset will not be on
  17 in 2026.
- **Day-one migration cost for affected users.** Users on 11 either
  upgrade their runtime or pin to whichever older SDK release we've
  shipped that supports 11. Until we publish a v1 there's no "older
  release" to pin to — so initially they have only the upgrade option.

## Code-Level Comparison

To make the cost concrete, here is the same response model and exception
hierarchy under each option.

### Response model

**JDK 11:**

```java
public final class Quote {
    private final String symbol;
    private final BigDecimal bid;
    private final BigDecimal ask;
    private final Instant timestamp;

    public Quote(String symbol, BigDecimal bid, BigDecimal ask, Instant timestamp) {
        this.symbol = symbol;
        this.bid = bid;
        this.ask = ask;
        this.timestamp = timestamp;
    }

    public String getSymbol() { return symbol; }
    public BigDecimal getBid() { return bid; }
    public BigDecimal getAsk() { return ask; }
    public Instant getTimestamp() { return timestamp; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quote)) return false;
        Quote q = (Quote) o;
        return Objects.equals(symbol, q.symbol)
            && Objects.equals(bid, q.bid)
            && Objects.equals(ask, q.ask)
            && Objects.equals(timestamp, q.timestamp);
    }

    @Override public int hashCode() {
        return Objects.hash(symbol, bid, ask, timestamp);
    }

    @Override public String toString() {
        return "Quote{symbol=" + symbol + ", bid=" + bid
            + ", ask=" + ask + ", timestamp=" + timestamp + "}";
    }
}
```

**JDK 17:**

```java
public record Quote(String symbol, BigDecimal bid, BigDecimal ask, Instant timestamp) {}
```

The ~30-line vs 1-line gap multiplied across ~20 response models is
~500–600 lines of mechanical, error-prone boilerplate that we either
write by hand (JDK 11) or get for free (JDK 17).

### Exception hierarchy

**JDK 11** — abstract base, non-exhaustive consumer switch:

```java
public abstract class MarketDataException extends RuntimeException { … }

public final class AuthenticationError extends MarketDataException { … }
public final class RateLimitError      extends MarketDataException { … }
// … etc.
```

```java
try {
    client.stocks().quote("AAPL");
} catch (MarketDataException e) {
    if (e instanceof RateLimitError) {
        RateLimitError re = (RateLimitError) e;
        // …
    } else if (e instanceof AuthenticationError) {
        // …
    }
    // No compile-time guarantee we handled every subtype.
    // Adding a new error subtype later silently slips past this code.
}
```

**JDK 17** — sealed, compiler-enforced exhaustiveness:

```java
public sealed abstract class MarketDataException extends RuntimeException
    permits AuthenticationError, BadRequestError, NotFoundError,
            RateLimitError, ServerError, NetworkError, ParseError { … }
```

```java
try {
    client.stocks().quote("AAPL");
} catch (MarketDataException e) {
    switch (e) {
        case RateLimitError      re -> // …
        case AuthenticationError ae -> // …
        case BadRequestError     br -> // …
        case NotFoundError       nf -> // …
        case ServerError         se -> // …
        case NetworkError        ne -> // …
        case ParseError          pe -> // …
    }
    // Compiler enforces exhaustiveness. Adding an 8th subtype in a
    // future major version breaks every consumer's switch — they get
    // a compile error, not a silent runtime miss.
}
```

This is not aesthetic. It's a real safety property the JDK 11 version
cannot offer.

## Claude's Recommendation

**JDK 17.**

The deciding factors:

1. **Records and sealed types are unusually well-matched to this SDK's
   surface.** The codebase is dominated by typed response models and a
   structured exception taxonomy — the two language features that
   arrived in 16/17 directly target both. The benefit recurs over the
   SDK's lifetime.
2. **JDK 17 is the modern-Java baseline.** Spring Boot 3, Jackson 3,
   and the dominant library ecosystem all require it. Choosing 17 in
   2026 is conservative-by-now, not aggressive.
3. **The free-LTS runway is materially longer.** ~2029 for 17 vs ~2027
   for 11. We avoid telling consumers within the SDK's first
   major-version cycle that their runtime is unsupported.
4. **The HTTP-dependency constraint already excluded JDK 8.** Once
   we've accepted that exclusion, the marginal cost of also excluding
   JDK 11 is much smaller than the gap from 8 to 11.

The strongest reasonable counter-recommendation is **JDK 11**, on the
basis that an SDK should err toward maximum reach when in doubt — and
that we genuinely don't have telemetry on Market Data API consumer JDK
versions. That's a defensible position; this ADR documents it for the
team to weigh.

If the team has any signal at all on JDK 11 holdouts among Market Data
API customers (support tickets mentioning JDK 11, GitHub issues, sales
conversations), that signal should override either of these
principle-based positions.

## Decision

*To be filled in by the team.*

## Consequences

Follow-on work implied by each option. The chosen option will be marked.

- **A (JDK 11):** keep `java.net.http.HttpClient`; hand-write all
  response POJOs (or accept Lombok / AutoValue and revisit the
  no-runtime-deps direction); use abstract-base exception hierarchy
  without compile-time exhaustiveness; build pipeline targets
  `javac --release 11`; plan a follow-up bump to 17 within the SDK's
  first major-version cycle as Temurin LTS for 11 winds down.
- **B (JDK 17):** records for all response models; sealed exception
  hierarchy with exhaustive `switch`; pattern matching available in
  implementation code; build pipeline targets `javac --release 17`.
  This shapes most later ADRs (response model design, exception design,
  HTTP client choice).

## References

- [Market Data SDK Requirements](../sdk-requirements.md)
- [Java SDK Requirements](../java-sdk-requirements.md)
- [JDK release schedule](https://www.java.com/releases/) and
  [Eclipse Temurin support roadmap](https://adoptium.net/support/)
- [JEP 395: Records](https://openjdk.org/jeps/395)
- [JEP 409: Sealed Classes](https://openjdk.org/jeps/409)
- [JEP 406 / 420: Pattern Matching for `instanceof` and `switch`](https://openjdk.org/jeps/406)
- [Spring Boot 3 system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [ADR-001 — Java-Only vs Multi-Language SDK](./ADR-001-java-only-vs-multi-language-sdk.md)
