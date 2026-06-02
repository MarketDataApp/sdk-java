# Options Review Guide — `10_options_resource`

This guide walks a reviewer through the `options` resource added on the `10_options_resource` branch. Like the [Refactor Review Guide](REFACTOR_REVIEW_GUIDE.md), it is organized by **flow**, not by file: each section names the parts that participate in one slice of behavior, explains how they fit, and calls out the non-obvious decisions.

This PR builds directly on the foundation that guide describes — transport, retry, rate-limit, `Response<T>`, and the `ParallelArrays` wire-format helper are all reused unchanged (except for one additive change, §4). If a mechanism here looks like it's assumed rather than explained, it's in the Refactor guide.

Suggested reading order for someone new to the change: §1 (what's here) → §2 (the Request convention) → §4 (the deserializer + nullable columns — the correctness-critical part) → §3 (chain filters) → §7 (subtle corners). That's the load-bearing shape in ~30 minutes.

All `file:line` citations target `HEAD` on this branch. Line numbers drift; if a citation looks off, search for the symbol it names.

## Table of contents

- [Running it locally](#running-it-locally)
1. [What this PR adds](#1-what-this-pr-adds)
2. [The Request-class convention](#2-the-request-class-convention)
3. [Chain request → query translation](#3-chain-request--query-translation)
4. [The shared option-row deserializer + optional columns](#4-the-shared-option-row-deserializer--optional-columns)
5. [The `quotes` multi-symbol fan-out](#5-the-quotes-multi-symbol-fan-out)
6. [lookup / expirations / strikes](#6-lookup--expirations--strikes)
7. [Subtle corners (finding-driven)](#7-subtle-corners-finding-driven)
8. [Out of scope for this review](#8-out-of-scope-for-this-review)
- [Reviewer checklist](#reviewer-checklist)

---

## Running it locally

```bash
make build                 # unit tests + Spotless + JaCoCo

# Integration tests hit the live API (gated). A token in .env or the env is required:
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest

# Consumer-side demo of every options call (runs in demo mode without a token):
make publish && make demo-quickstart
```

The integration suite (`OptionsIntegrationTest`) was run green against `api.marketdata.app` as part of this PR — 9 tests, shape assertions. `QuickstartApp`'s `optionsExamples(...)` is the "what consumer code looks like" surface.

---

## 1. What this PR adds

### 1.1 Public API surface (new)

What a consumer can now `import` and call, on top of the existing surface:

```
com.marketdata.sdk.OptionsResource              (returned from client.options())

com.marketdata.sdk.options.OptionsLookupRequest        / OptionsLookup
com.marketdata.sdk.options.OptionsExpirationsRequest   / OptionsExpirations
com.marketdata.sdk.options.OptionsStrikesRequest       / OptionsStrikes (+ ExpirationStrikes)
com.marketdata.sdk.options.OptionsQuoteRequest         / OptionsQuotes (+ OptionQuote)
com.marketdata.sdk.options.OptionsQuotesRequest
com.marketdata.sdk.options.OptionsChainRequest         / OptionsChain
com.marketdata.sdk.options.ExpirationFilter (sealed)   / StrikeFilter (sealed)
com.marketdata.sdk.options.OptionSide / StrikeRange    (enums)
```

`OptionsResource` is `public final` with a **package-private constructor** (ADR-007) — consumers reach it through `client.options()` (`MarketDataClient` wires it at construction) but cannot instantiate it. Response models and requests live in the public `com.marketdata.sdk.options` subpackage, mirroring `utilities`.

### 1.2 Files to review, by role

| Area | Files | What to check |
|---|---|---|
| Resource façade | `OptionsResource.java` | URL/param translation, fan-out, deserializer wiring |
| Requests | `options/Options*Request.java`, `ExpirationFilter`, `StrikeFilter`, `OptionSide`, `StrikeRange` | Builder validation, sealed-type modeling |
| Response models | `options/Options*.java`, `OptionQuote`, `ExpirationStrikes` | Field types (esp. nullable greeks) |
| Deserializers | `OptionsLookupDeserializer`, `OptionsExpirationsDeserializer`, `OptionsStrikesDeserializer` + `optionRowsDeserializer` in `OptionsResource` | Wire-shape correctness, strictness |
| Reused infra (changed) | `ParallelArrays.java` | The additive optional-column overload |
| New infra | `MarketDataDates.java`, `PathSegments.java` | Date parsing, URL encoding |
| Wiring | `MarketDataClient.java` (`+10 lines`) | `client.options()` accessor |

### 1.3 What did **not** change

The transport, retry, rate-limit, status-cache, `Response<T>`, and exception layers are untouched. `ParallelArrays` is the only pre-existing class with a behavior-relevant change, and it is purely additive (the old 4-arg `zip` / 3-arg `listDeserializer` still exist and delegate — `ParallelArrays.java:70`, `:186`). Confirm no existing `utilities` test regressed.

---

## 2. The Request-class convention

This PR establishes the SDK-wide convention: **one Builder-based request class per endpoint, no `String` overloads.**

```java
// no-optionals endpoints: static of(...)
OptionsLookupRequest.of("AAPL 1/16/2026 $200 Call");
// optionals: builder(required...)... .build()
OptionsExpirationsRequest.builder("AAPL").strike(150.0).date(LocalDate.of(2024, 1, 17)).build();
```

What to check per request class:

- **Required args are constructor/factory params**; optionals are fluent setters. The required arg can't be omitted at the call site.
- **Cross-field validation is in `build()`**, throwing `IllegalArgumentException` with a message naming the conflict — e.g. `OptionsQuoteRequest.build()` rejects `date` + `from`/`to` together, and the shared `validateWindow(...)` (`OptionsQuoteRequest.java`) rejects `countback` with `date` or `from`. `OptionsQuotesRequest` reuses that same validator.
- **Getters are plain reads** (no work) — the resource reads them to build a `RequestSpec`. (The Request can't reach `RequestSpec` directly: it lives in a public subpackage, `RequestSpec` is package-private in the root.)

### 2.1 Sealed types for mutually-exclusive groups

`chain` has two groups where combining variants is undefined server-side. They're modeled as sealed interfaces so exclusivity is **compiler-enforced** — there's a single setter per group, and you can only pass one variant:

- `ExpirationFilter` (`options/ExpirationFilter.java`): `OnDate` / `Dte` / `Between` / `MonthYear` / `All`. Factory methods validate at creation (`dte` ≥ 0, `between` ordered, `month` 1–12).
- `StrikeFilter` (`options/StrikeFilter.java`): `Exact` / `Range` / `Comparison` (with `Operator` GT/GTE/LT/LTE).

Pair constraints that the type system can't express (`minBid ≤ maxBid`) stay as runtime checks in `OptionsChainRequest.build()`.

> Reviewer note: this is the pattern future resources are expected to copy. If you disagree with it, this is the PR to say so — it's load-bearing for `stocks`/`funds`/`markets`.

---

## 3. Chain request → query translation

`chain` is the densest logic in the PR: ~25 optional parameters mapped to query string. The translation is `applyChainParams` (`OptionsResource.java:306`), called from `chainAsync` (`:291`).

### 3.1 The shape

`applyChainParams` is a flat sequence of `if (r.foo() != null) b.query("foo", ...)` for the independent params, plus two delegations:

- `applyExpirationFilter(b, f)` (`:372`) — pattern-matches the sealed `ExpirationFilter`:

  | Variant | Wire |
  |---|---|
  | `OnDate(date)` | `?expiration=YYYY-MM-DD` |
  | `Dte(days)` | `?dte=N` |
  | `Between(from, to)` | `?from=…&to=…` |
  | `MonthYear(year, month)` | `?month=M&year=YYYY` |
  | `All` | `?expiration=all` |

  **Check the trailing `else { throw IllegalStateException }`** (`:388`). The `instanceof` chain isn't compiler-checked for exhaustiveness, so the guard exists to fail loudly if a future variant is added without a branch (it mirrors `strikeFilterWireValue`'s guard). This is unreachable today — it won't show in coverage, by design.

- `strikeFilterWireValue(f)` (`:393`) → the `?strike=` value: `150` (exact), `140-160` (range), `>150` / `>=150` (comparison). Strikes render without trailing zeros via `formatStrike` (`:408`).

### 3.2 What to verify

- Every getter on `OptionsChainRequest` has a corresponding line in `applyChainParams`. A param that exists on the request but is never read would silently do nothing. (Cross-check the builder setters against `applyChainParams`.)
- `minBid`/`maxBid`/`minAsk`/`maxAsk` **are** real backend params (verified against the handler) — they were almost cut as "phantom"; they're not.
- Date params use `ISO_LOCAL_DATE` (`YYYY-MM-DD`) consistently.

The unit tests `chainExpirationFilter*`, `chainStrikeFilter*`, and the per-param URL tests in `OptionsResourceTest` assert the exact query string for each branch.

---

## 4. The shared option-row deserializer + optional columns

**This is the correctness-critical section.** `quotes` and `chain` emit the same per-contract parallel-arrays row, so they share one deserializer: `optionRowsDeserializer(wrapper)` (`OptionsResource.java:110`), registered for both `OptionsQuotes` and `OptionsChain` in `wireFormatModule()` (`:57`).

### 4.1 The row schema

`OPTION_ROW_FIELDS` (`:68`) lists the 25 **required** columns; `OPTION_OPTIONAL_ROW_FIELDS` (`:102`) lists `rho` as optional. Each row builds an `OptionQuote` (`options/OptionQuote.java`).

### 4.2 The nullable-model-values change (the heart of the review)

The model-derived values — `iv`, `delta`, `gamma`, `theta`, `vega`, `rho` — are typed `@Nullable Double` and read via `row.dblOrNull(...)`. Everything else (bid/ask/last/sizes/strike/…) stays primitive `double`/`long`, read via the strict `row.dbl(...)`/`row.lng(...)`.

**Why:** on historical/illiquid rows the API returns `null` for the model values (no model output that day). The strict-by-default `ParallelArrays.Row` accessors throw on a null cell — which surfaced as a live `ParseError` on a `countback` query (see §7). Making the six model values nullable is the fix.

Two distinct kinds of "nullable" are in play here — verify you follow both:

| Field | In which list | Decode | Tolerates |
|---|---|---|---|
| `rho` | `OPTION_OPTIONAL_ROW_FIELDS` | `dblOrNull` | column **absent entirely** *and* null cell |
| `iv`, `delta`, `gamma`, `theta`, `vega` | `OPTION_ROW_FIELDS` (required) | `dblOrNull` | null **cell** (column must still be present) |

So `rho` may be missing as a whole array; `iv`/greeks must be present as arrays (a missing `iv` column is still a server bug → `ParseError`) but individual cells may be `null`.

### 4.3 The `ParallelArrays` additive change

`ParallelArrays` gained (all additive — old signatures delegate):

- `zip(p, root, fields, optionalFields, rowBuilder)` (`:87`). After the required-field loop, optional fields are processed (`:129`): absent/null/non-array → skipped; present → length-checked like any column.
- `listDeserializer(fields, optionalFields, rowBuilder, wrapper)` (`:197`).
- `Row.dblOrNull(field)` (`:244`, impl `:299`): returns `null` if the column isn't in the row's map **or** the cell is null/missing; throws `typeMismatch` only on a present-but-non-number cell. Leniency covers **absence, not corruption.**

What to verify:
- The old behavior is unchanged for every existing caller (utilities, lookup/expirations/strikes use the strict path). The `online`-column-regression reasoning in the `ParallelArrays` class javadoc still holds — strict is still the default.
- `dblOrNull` does **not** route through `cell(field)` (which throws on unknown field); it checks `arrays.containsKey` first. Confirm an absent optional column can't throw.

### 4.4 Tests that document this

`OptionsResourceTest`:
- `quoteDecodesAllFields` — all values present, including a populated row.
- `quoteDecodesRhoWhenPresent` / the rho-absent assertion — `rho` round-trips and is `null` when omitted.
- `quoteDecodesNullModelValuesAsNull` — `iv`+greeks all `null` in the body decode to `null` without a `ParseError`, while a market field on the same row stays populated. This reproduces the live-API failure.

---

## 5. The `quotes` multi-symbol fan-out

The backend `quotes` endpoint takes a single `optionSymbol` per call. The SDK's `quotes(...)` (`OptionsResource.java:257`) accepts N symbols and fans out one request each.

Walk `quotesAsync`:
- One `buildQuoteSpec(symbol, date, from, to, countback)` per symbol (`:264`), each dispatched through the normal transport (so each rides the 50-permit `AsyncSemaphore`, retry, preflight — nothing special).
- Results collected into a `LinkedHashMap` keyed by the **input symbol**, insertion order preserved, so per-symbol `Response` metadata (`statusCode`, `isNoData`, `rawBody`, `requestId`) stays observable.
- **Fail-fast:** `CompletableFuture.allOf(...)` means the map's future completes exceptionally if any single request fails. Confirm you're comfortable with that semantic vs. partial-success — it's deliberate (a partial map would hide failures).

`quote(...)` (single) and `quotes(...)` (multi) share `buildQuoteSpec`; the historical-window params (`date`/`from`/`to`/`countback`) apply identically to every symbol.

Verify: `quotesFansOutToMultipleContracts` (integration) and `quotesAttachesCountbackToEachFanOut` / `quotesAttaches*` (unit).

---

## 6. lookup / expirations / strikes

The three simpler endpoints, each with its own hand-written deserializer (the wire shapes don't fit the parallel-arrays helper):

- **lookup** (`OptionsResource.java:152`) — flat `{"s":"ok","optionSymbol":"…"}`. `OptionsLookupDeserializer`. The path is URL-encoded per-segment via `PathSegments.encode` so `AAPL 7/26/23 $200 Call` survives (`/` preserved, space → `%20`, `$` encoded). See `lookupUrlEncodesSpacesAndReservedChars`.
- **expirations** (`:173`) — parallel `expirations[]` + scalar `updated`. `OptionsExpirationsDeserializer`. Decodes epochs to `ZonedDateTime` at market-midnight (America/New_York) via `MarketDataDates`. `no_data` envelope → empty list, `updated` null.
- **strikes** (`:201`) — unusual shape: one top-level key **per expiration date** plus `s`/`updated`. `OptionsStrikesDeserializer` is strict — an unrecognized non-date key throws (`strikesUnrecognizedTopLevelKeyThrowsParseError`), a non-numeric strike throws, missing `updated` throws.

Check `MarketDataDates` (new) handles all three `dateformat` variants (unix / ISO-string / spreadsheet) uniformly, since the typed surface is `dateformat`-agnostic.

---

## 7. Subtle corners (finding-driven)

| # | Corner | Where | What to know |
|---|---|---|---|
| 7.1 | **Null model values** | §4.2 | Historical/illiquid rows return null `iv`/greeks. Strict parse would `ParseError`. Fixed via nullable `@Nullable Double` + `dblOrNull`. Found by running live IT. |
| 7.2 | **HTTP 203 is success** | `OptionsIntegrationTest` | API returns `203 Non-Authoritative Information` for cached/delayed data. The SDK treats it as success; IT assert `200 || 203` so they don't flap with market hours. |
| 7.3 | **`expiration=all` ≠ no filter** | `ExpirationFilter.All`, `applyExpirationFilter` | Omitting the expiration filter makes the API return only the **front-month**; `all()` returns every expiration. Verified against the backend's `get_default_expiration_date` (`.head(1)`). Both are exercised in IT (`chainExpirationAllSpansMultipleExpirations`). |
| 7.4 | **`countback` validation** | `OptionsQuoteRequest.validateWindow` | Must be positive; mutually exclusive with `date` and `from` ("if you use from, countback is not required" per the API doc). Canonical use is `to` + `countback`. |
| 7.5 | **Exhaustiveness guard** | `applyExpirationFilter` `else throw` | Sealed `instanceof` chains aren't compiler-exhaustive; the guard fails loudly on a future unmatched variant instead of dropping the filter silently. |
| 7.6 | **`quotes` is fan-out, not bulk** | `quotesAsync` | The backend path takes one symbol; the docstring's comma-separated claim is wrong (verified by reading the handler). The SDK fans out N concurrent calls. |
| 7.7 | **`source` intentionally absent** | — | Internal provider param, not in the public schema / requirements / Python SDK. Deliberately not exposed. |

---

## 8. Out of scope for this review

Do **not** flag these as missing — they're deferred decisions, documented in [`PR.md`](../PR.md):

- **§3 universal parameters** (`format`/CSV, `dateformat`, `columns`, `limit`, `offset`, `headers`, `human`, `mode`) — no consumer-facing API on options yet; lands with `stocks`. The `RequestSpec.Builder` plumbing exists but is package-private.
- **ADR-008 / ADR-009 + requirements §2.x + acceptance checklist** — pending the ADR-first workflow, to be drafted post-merge.
- **§13 JaCoCo 100% threshold** — deferred until the full resource layer lands.
- **§8 per-response rate-limit snapshot** on `Response<T>` — still client-level.

---

## Reviewer checklist

- [ ] Request convention: every endpoint has a Builder request, no `String` overloads; required args non-optional; cross-field validation in `build()`.
- [ ] Sealed `ExpirationFilter` / `StrikeFilter` correctly translate every variant in `applyChainParams`, with the `else throw` guard.
- [ ] `applyChainParams` reads **every** `OptionsChainRequest` getter (no silently-dropped param).
- [ ] Nullable model values: `iv`/`delta`/`gamma`/`theta`/`vega`/`rho` are `@Nullable Double` + `dblOrNull`; market fields stay primitive + strict.
- [ ] `ParallelArrays` change is additive; existing strict callers unchanged; `dblOrNull` can't throw on an absent column.
- [ ] `quotes` fan-out: per-symbol map, insertion order, fail-fast semantics acceptable.
- [ ] Hand-written deserializers (lookup/expirations/strikes) stay strict on unexpected shapes.
- [ ] Unit (`./gradlew build`) and integration (`MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest`) both green.
- [ ] Deferred items (§8) understood and not blocking.
