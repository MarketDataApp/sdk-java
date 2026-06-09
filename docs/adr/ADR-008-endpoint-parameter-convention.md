# ADR-008: Endpoint Parameter Convention (Request Objects)

## Status

**Accepted** (2026-06-08). Implemented on branch `10_options_resource`.

Decided as **Option A** (request object + builder) — the `Consumer<Builder>` overload (Option
B) was *not* adopted. Two pieces the original Context flagged but didn't resolve were settled
here in the Decision below: the **§3 universal parameters** are a fluent resource-level config
(not a request-object overload), and the **output/format axis** is a typed **resource facet**
(not a parameter). The Context and Options-Considered sections are preserved as the decision
rationale; the Decision/Consequences reflect what shipped.

## Context

ADR-006 fixed *how many* surfaces each endpoint exposes (sync + async
parity). It did **not** fix *how an endpoint accepts its parameters* —
the call shape a consumer actually types. The `options` resource (the
second resource after `utilities`, and the template the remaining
`stocks` / `funds` / `markets` resources will copy) is the first place
this decision becomes load-bearing, because `chain` carries the richest
filter surface in the API (~25 query parameters, plus two groups of
mutually-exclusive filters).

The cross-language sibling SDKs have already answered this question, and
they answered it the same way:

```python
# sdk-py — kwargs bag
client.options.chain("AAPL", side="call", strike_limit=10, min_open_interest=100)
```

```typescript
// sdk-js — options-object bag
client.options.chain("AAPL", { side: "call", strikeLimit: 10, minOpenInterest: 100 });
```

Both use *positional required argument + one flat optional bag*. Both
validate at runtime (Pydantic / Zod). Neither models the
mutually-exclusive filter groups — in both, you can pass `dte` **and**
`expiration` together and the backend arbitrates. `sdk-js` even allows
unknown keys through (`.passthrough()`).

Java cannot copy that shape directly: it has no keyword arguments and no
object literals. The idiomatic Java substitutes are a request object
with a builder, a `Consumer<Builder>` lambda, a transport-bound fluent
builder, or a flat "parameters" object mirroring the siblings. The
choice matters because:

- It is a **public-API decision**, hard to reverse without breaking
  changes, and it will be **replicated across every future resource** —
  so it should be decided once, here, not endpoint-by-endpoint.
- Java is the **only** SDK in the family whose type system can make the
  mutually-exclusive filter groups *unrepresentable* rather than merely
  *validated*. `chain` models them as sealed types (`ExpirationFilter` →
  `OnDate` / `Dte` / `Between` / `MonthYear` / `All`; `StrikeFilter` →
  `Exact` / `Range` / `Comparison`) reached through a single setter, so
  "pick one variant" is enforced by `javac`. Whatever convention we pick
  must preserve that, because it is the one dimension where the Java SDK
  is strictly safer than its siblings.
- ADR-006 parity and the **deferred §3 universal parameters**
  (`format`, `dateformat`, `columns`, `mode`, …, landing with `stocks`)
  both interact with the convention: a request object adds universal
  params as a second overload; a fluent terminal folds them in as more
  setters.

Filter set held constant across the options below: **calls only, strike
limit 10, minimum open interest 100.**

## Options Considered

### Option A — Request object + builder (the PR as written)

Each endpoint takes one immutable request object, constructed via a
static `builder(required…)` (or `of(required…)` when there are no
optionals). One signature per endpoint, both surfaces.

```java
client.options().chain(
    OptionsChainRequest.builder("AAPL").side(OptionSide.CALL).strikeLimit(10).minOpenInterest(100).build());

client.options().chain(OptionsChainRequest.of("AAPL"));   // no filters
```

**Pros**

- The request object is **inert and decoupled** — no client reference.
  Reusable across calls and clients, inspectable, loggable, and
  unit-testable (assert the query translation without a transport).
- One object feeds both `chain(req)` and `chainAsync(req)` with **zero
  duplicated parameter surface** — ADR-006 parity is trivial.
- **Uniform call shape** across all six endpoints regardless of
  parameter richness; the future universal-params overload retrofits
  cleanly as `chain(req, universalParams)`.
- Immutable, matching the records/immutability ethos (ADR-005/007).
- Sealed filters live naturally on the builder.

**Cons**

- `.build()` ceremony on every inline call.
- Naming stutter: `options().chain(…)` then `OptionsChainRequest`.
- Loses to a kwargs bag / object literal on raw terseness — Java will
  never win that contest.

### Option B — Option A plus a `Consumer<Builder>` overload

Keep everything in Option A; add an additive overload that hides the
builder naming and `.build()` for the inline case.

```java
client.options().chain("AAPL", b -> b.side(OptionSide.CALL).strikeLimit(10).minOpenInterest(100));
client.options().chain("AAPL");                         // bare overload for the no-filter case
```

**Pros**

- Closest spiritual match to how `sdk-py` / `sdk-js` actually solved it
  (required positional + optional configuration), so cross-SDK muscle
  memory transfers.
- Keeps **all** of Option A's properties — the request object stays
  inert and decoupled; `chain(req)` survives for reuse and for
  conditional/dynamic filter construction (which reads badly inside a
  lambda).
- Purely additive over Option A: ~6 lines per endpoint delegating to the
  existing request-object method; no change to the request classes.

**Cons**

- Reopens the explicit "no `String` convenience overloads, uniform call
  shape" decision the current PR made on purpose.
- Overload count: `chain(req)` + `chain(String, Consumer)` +
  `chain(String)` = 3 signatures × sync/async = 6 per endpoint, and the
  deferred universal-params overload can multiply that again unless
  universal params fold into the builder.
- Per call site you can avoid the lambda **or** the builder naming, but
  not both — the lambda exists precisely to hide the builder, so the
  lambda-free form (`chain(req)`) necessarily names `builder()`/`build()`.

### Option C — Transport-bound fluent builder + terminal verb

`chain("AAPL")` returns a builder wired to the transport; filters are
fluent setters; a mandatory terminal (`fetch()` / `fetchAsync()`)
executes. This is the only shape that drops the lambda **and** the
builder naming **and** `.build()` simultaneously.

```java
client.options().chain("AAPL").side(OptionSide.CALL).strikeLimit(10).minOpenInterest(100).fetch();
```

**Pros**

- Shortest possible call site; closest to the siblings' brevity.
- Universal params fold in as more setters before the terminal — **no
  overload growth ever**.
- Uniform if applied to all six endpoints.

**Cons**

- The builder is **fused to the transport** — it *is* a live request,
  not data. Loses the free-standing, reusable, unit-testable spec unless
  a parallel inert `OptionsChainRequest` + `chain(req)` surface is kept
  too (two ways to do everything).
- **Dangling-terminal footgun:** `chain("AAPL").side(CALL);` (no
  terminal) compiles and silently does nothing. Java's type system
  cannot force termination; only an ErrorProne `@CheckReturnValue`
  backstop catches it.
- Moves the ADR-006 sync/async pair off the resource method onto the
  terminal verb — an **ADR-006 amendment**, not a free refactor.
- Makes Java the **call-shape outlier** among the three SDKs (neither
  sibling has a terminal verb).

### Option D — Flat parameters object (mirror the siblings)

A single `OptionsChainParams` object (or a long overloaded signature)
with every filter as an independent optional field, mirroring the
Python/JS bag exactly — no sealed types.

```java
var p = new OptionsChainParams();
p.setSide(OptionSide.CALL); p.setStrikeLimit(10); p.setMinOpenInterest(100);
client.options().chain("AAPL", p);
```

**Pros**

- Maximum cross-SDK structural consistency.
- Familiar to consumers coming from `sdk-py` / `sdk-js`.

**Cons**

- **Throws away the one Java advantage:** mutually-exclusive filters
  become independent fields again, so `dte` + `expiration` is back to a
  runtime/server error instead of a compile error. Re-implements the
  siblings' weakest property in the one language that doesn't have to.
- Mutable params object cuts against the immutability ethos.
- Validation regresses from "unrepresentable" to "checked in `build()`/
  on the wire."

## Claude's Recommendation

**Option A as the canonical, ADR-blessed form, plus Option B's
`Consumer<Builder>` overload as the ergonomic front door.**

The deciding factors:

1. Terseness is unwinnable for Java against an object literal or kwargs —
   so it should not be the optimization target. The Java SDK's
   differentiator is the **compile-time sealed filters** (Options A/B/C
   keep them; Option D discards them). Protect that; it is the only
   column where Java leads its siblings.
2. The **inert, decoupled request object** (A/B) buys reuse,
   testability, trivial ADR-006 parity, and freedom from the
   dangling-terminal footgun. Option C trades all of that for a cosmetic
   win and forces an ADR-006 amendment plus cross-SDK divergence.
3. Option B's overload is **purely additive** and recovers the siblings'
   call ergonomics (`chain("AAPL", b -> …)` / `chain("AAPL")`) without
   sacrificing anything in A. Its only real cost — overload count — is a
   conscious, bounded trade, not the open-ended growth Option C's
   detractors and the original PR feared.
4. Adopting B is an **all-or-nothing convention**: if `chain` gets the
   overload, every endpoint should, so the SDK has one front door, not a
   per-endpoint coin flip. That uniformity is exactly what an ADR is for.

The strongest counter-recommendation is **Option A alone** (the PR as
written): one signature per endpoint is the simplest possible surface
and the cleanest universal-params retrofit. The case against it is
purely ergonomic — `OptionsChainRequest.builder(…).build()` at every
call site — and Option B answers that without giving anything up. If the
team values minimal surface over call-site ergonomics, shipping A in the
options PR and adding B in a follow-up is a reasonable middle path (the
overload is additive and non-breaking, so deferring it costs nothing
structurally).

## Decision

**Option A — one immutable request object per endpoint** (`builder(required…)` /
`of(required…)`), feeding both the sync and async surfaces. **No `String` convenience
overloads and no `Consumer<Builder>` overload (Option B):** the team chose the minimal,
uniform surface — the "counter-recommendation" the analysis above weighed — over the lambda
front door. B stays purely additive, so it can be added later if call-site ceremony bites; it
was not worth the overload-count cost up front. The sealed mutually-exclusive filter groups
(`ExpirationFilter`, `StrikeFilter`) are retained unchanged.

Two things resolved **differently from the original recommendation**, because a request-object
overload turned out to be the wrong axis for them:

- **Universal parameters are not a second request-object overload.** They are set fluently on
  the **resource** — `client.options().dateFormat(…).mode(…).limit(…).offset(…).columns(…)`,
  an immutable configured value carried across endpoints via `RequestConfig`. Type-preserving
  params (`dateFormat`/`mode`/`limit`/`offset`/`columns`) sit on the typed resource; the
  output-shaping `human`/`headers` live only on the CSV facet (they reshape the payload, so they
  don't cohere with the typed decode).
- **The output/format axis** — which a request-object parameter *cannot* express in a
  statically-typed language (the return type would have to vary at runtime) — is selected via a
  **typed resource facet** (`.asCsv()` → `CsvResponse`; `.asHtml()` built but not yet exposed),
  not a parameter.

Options C (transport-bound fluent terminal) and D (flat params object) were considered and
rejected — C because it sacrifices the decoupled request object, introduces an un-enforceable
dangling-terminal footgun, amends ADR-006, and makes Java the cross-SDK call-shape outlier; D
because it discards the compile-time mutual-exclusivity guarantee that is the Java SDK's one
advantage over its siblings.

### Nullable response fields + Option A (the cost of `columns`)

Exposing `columns` on the **typed** path forced a response-side decision. A projected response
omits the columns you didn't ask for, so the typed model can't keep "always present" fields:
**every response-row field is `@Nullable`** (boxed) — including structural ones like `strike`
and `bid`, not just the legitimately-optional values (the option greeks / `iv`).

To stop that from degrading into *silent* failures, the deserializer reads every column
leniently but then enforces **Option A**: it cross-references the columns the consumer actually
requested (threaded in via a parser attribute) and raises a `ParseError` if a **required**
column that was requested — or wasn't projected away (no `columns` filter ⇒ all required
columns are implicitly requested) — is missing from the response. The contract that buys:

- a `null` a consumer sees means **only** "I projected it away with `columns`" or "a
  legitimately-optional model value (greek/IV)";
- it **never** means "the backend silently dropped a required field" — that is always a
  `ParseError`.

So the nullable fields do **not** weaken the strict-by-default decode; they just make
projection representable. (Greek presence is additionally inspectable via
`OptionQuote.presentGreeks()` / `greek(Greek)`.)

## Consequences

What actually shipped, and what was left out.

- **A (shipped):** One signature per endpoint; call sites always name
  `FooRequest.builder(…).build()` (or `of(…)`); no lambda overloads. Shipped for `options` and
  is the template every future resource (`stocks`/`funds`/`markets`) copies.
- **B (not adopted):** the `foo(String, Consumer<Builder>)` / `foo(String)` overloads were
  considered and deliberately left out — purely additive, so revisitable without a breaking
  change if call-site ceremony proves annoying in practice.
- **Universal parameters:** fluent setters on the **resource** (`RequestConfig`), applied
  across endpoints — *not* the "second request-object overload" the recommendation originally
  envisioned. Implemented for `options`; `human`/`headers` and the format facet land on the CSV
  view.
- **C (fluent terminal) / D (flat params object):** not adopted (rejected as above).

## References

- [Market Data SDK Requirements §3 Universal Parameters, §Language-Idiomatic Design](../sdk-requirements.md)
- [Java SDK Requirements §2 — Kotlin interop](../java-sdk-requirements.md)
- [ADR-005 — JSON Library](./ADR-005-json-library.md) — records / immutability ethos
- [ADR-006 — Async API Surface](./ADR-006-async-api-surface.md) — sync/async parity that the terminal-verb option (C) would amend
- [ADR-007 — Internal API Encapsulation](./ADR-007-internal-api-encapsulation.md) — package-private constructors on resource façades and request types
- Sibling SDKs (not committed in this repo): `sdk-py` `client.options.chain(...)` (kwargs bag); `sdk-js` `client.options.chain(...)` (options-object bag, Zod `.passthrough()`)
