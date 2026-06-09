# Options Review Guide — `10_options_resource`

This guide walks a reviewer through the `options` resource added on the `10_options_resource` branch, **and** the response-model + parameter conventions it establishes SDK-wide. Like the [Refactor Review Guide](REFACTOR_REVIEW_GUIDE.md), it is organized by **flow**, not by file.

This PR builds on the transport/retry/rate-limit foundation that guide describes (reused unchanged), but it **does** change the response layer: the old generic `Response<T>` is replaced by `MarketDataResponse<T>` + named per-endpoint types, and `ParallelArrays` gains lenient accessors. `utilities` is migrated onto the new model too.

Suggested reading order: §1 (what's here) → §2 (the response model) → §5 (the deserializer: nullable + columns + Option A — the correctness-critical part) → §3 (request convention) / §4 (chain filters) → §6 (universal params + facets) → §9 (subtle corners). ~40 minutes for the load-bearing shape.

`file:line` citations target `HEAD` on this branch; line numbers drift — if one looks off, search for the symbol it names.

## Table of contents

- [Running it locally](#running-it-locally)
1. [What this PR adds](#1-what-this-pr-adds)
2. [The response model](#2-the-response-model)
3. [The Request-class convention](#3-the-request-class-convention)
4. [Chain request → query translation](#4-chain-request--query-translation)
5. [The option-row deserializer: nullable fields + columns + Option A](#5-the-option-row-deserializer-nullable-fields--columns--option-a)
6. [Universal parameters + the CSV/HTML facets](#6-universal-parameters--the-csvhtml-facets)
7. [The `quotes` multi-symbol fan-out](#7-the-quotes-multi-symbol-fan-out)
8. [lookup / expirations / strikes](#8-lookup--expirations--strikes)
9. [Subtle corners (finding-driven)](#9-subtle-corners-finding-driven)
10. [Out of scope for this review](#10-out-of-scope-for-this-review)
- [Reviewer checklist](#reviewer-checklist)

---

## Running it locally

```bash
make build                 # unit tests + Spotless + JaCoCo (JDK 17)

# Integration tests hit the live API (gated). A token in .env or the env is required:
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest

# Full options demo against the mock server (every endpoint + all params, CSV facet,
# columns projection, Option A). Needs the mock server up:
make publish && make mock-server   # (in one terminal)
make demo-options                  # (in another)   — or: ./gradlew -p examples/consumer-test runOptions
```

`OptionsIntegrationTest` (10 tests, shape assertions) was run green against `api.marketdata.app`. `OptionsApp` (`examples/consumer-test`) is the "what consumer code looks like" surface — it scripts the mock server's responses to demonstrate the columns/Option-A scenarios.

---

## 1. What this PR adds

### 1.1 Public API surface (new)

```
com.marketdata.sdk.MarketDataResponse<T>        (interface every response implements; T values() + metadata)
com.marketdata.sdk.OptionsResource              (returned from client.options())
com.marketdata.sdk.OptionsCsvResource           (returned from .asCsv())
com.marketdata.sdk.CsvResponse / HtmlResponse   (raw-text responses)
com.marketdata.sdk.Options{Chain,Quotes,Strikes,Expirations,Lookup}Response   (named per-endpoint responses)

com.marketdata.sdk.options.Options{Lookup,Expirations,Strikes,Quote,Quotes,Chain}Request
com.marketdata.sdk.options.{OptionQuote, ExpirationStrikes}        (row records — every field @Nullable)
com.marketdata.sdk.options.ExpirationFilter (sealed) / StrikeFilter (sealed)
com.marketdata.sdk.options.OptionSide / StrikeRange / Greek        (enums)
```

`OptionsResource` (and `OptionsCsvResource`) are `public final` with **package-private constructors** (ADR-007) — reached through `client.options()` / `.asCsv()`, never instantiated directly. The response wrapper types live in the **root** package (like the old `Response<T>` did, so resource façades can construct them via package-private constructors); the request/row records live in the public `com.marketdata.sdk.options` subpackage.

### 1.2 Files to review, by role

| Area | Files | What to check |
|---|---|---|
| Response model | `MarketDataResponse.java`, `AbstractMarketDataResponse.java`, `Options*Response.java`, `CsvResponse`/`HtmlResponse` | `values()` payload typing, metadata, the package-private-constructor wiring |
| Resource façade | `OptionsResource.java` | universal-param config, URL/param translation, deserializer + Option A, fan-out, `asCsv()`/`asHtml()` |
| CSV/HTML facets | `OptionsCsvResource.java`, `OptionsHtmlResource.java` | reuse of the static `*Spec` builders, `format=csv/html`, fan-out mirroring |
| Requests | `options/Options*Request.java`, `ExpirationFilter`, `StrikeFilter`, `OptionSide`, `StrikeRange` | Builder validation, sealed-type modeling |
| Row records | `options/OptionQuote.java`, `ExpirationStrikes` | **all fields `@Nullable`**, greeks helpers (`presentGreeks`/`greek`) |
| Reused infra (changed) | `ParallelArrays.java`, `JsonResponseParser.java`, `RequestConfig.java` | OrNull accessors; the `requestedColumns` attribute threading |
| Wiring | `MarketDataClient.java` | `client.options()`; the `StatusCache` supplier adapted to `r.values()` |

### 1.3 What changed in shared layers (was "unchanged")

- **`Response<T>` is deleted.** Both `options` and `utilities` use named `MarketDataResponse<T>` types now. Confirm nothing still imports `Response`.
- **`ParallelArrays`** gained lenient `textOrNull`/`lngOrNull`/`boolOrNull`/`nodeOrNull` (alongside the pre-existing `dblOrNull`). The strict `text`/`dbl`/`lng`/`bool`/`node` accessors are unchanged — `utilities`' `ApiStatus` deserializer still uses them, so confirm no `utilities` test regressed.
- **`JsonResponseParser`** gained a `parse(env, type, requestedColumns)` overload that sets a Jackson context attribute (`REQUESTED_COLUMNS_ATTR`). The no-columns `parse(env, type)` delegates with an empty list.
- Transport, retry, rate-limit, status-cache, and exception layers are untouched.

---

## 2. The response model

Every endpoint returns a **named** type implementing `MarketDataResponse<T>`:

```java
interface MarketDataResponse<T> {
    T values();                 // the flat payload, typed per endpoint
    int statusCode();  boolean isNoData();  String requestId();  java.net.URI requestUrl();
    String json();  boolean isJson();  boolean isCsv();  boolean isHtml();
    void saveToFile(Path path);
}
```

- `values()` is the **one** uniform data accessor. `OptionsChainResponse implements MarketDataResponse<List<OptionQuote>>` → `values()` is the rows; `OptionsLookupResponse implements MarketDataResponse<String>` → `values()` is the scalar OCC symbol. `OptionsStrikesResponse`/`OptionsExpirationsResponse` additionally expose `updated()`.
- **One hop, no double-unwrap:** `T` is the flat payload (a `List`, a `String`), not a sub-container.
- `AbstractMarketDataResponse<T>` (package-private, root) holds the payload + metadata and implements every accessor; the named types are thin subclasses with a package-private constructor.

What to check:
- `values()` return types match the wire shape per endpoint (rows / list / scalar).
- The metadata surface matches §11.5 detectors + the SDK's §13.5 additions (`requestId`, `requestUrl`, `json`). `rawBody()` (byte[]) was dropped — `json()` (the body as text) replaces it.
- `MarketDataClient`'s `StatusCache` supplier was adapted: `statusAsync().thenApply(r -> new ApiStatus(r.values()))`.

---

## 3. The Request-class convention

Unchanged from the original design: **one Builder-based request class per endpoint, no `String` overloads.**

```java
OptionsLookupRequest.of("AAPL 1/16/2026 $200 Call");                  // no-optionals: of(...)
OptionsExpirationsRequest.builder("AAPL").strike(150.0).build();      // optionals: builder(...)…build()
```

- Required args are factory params (can't be omitted); optionals are fluent setters; cross-field validation in `build()` (e.g. the shared `validateWindow(...)` rejects `date` + `from`/`to`, and `countback` with `date`/`from`).
- Getters are plain reads; the resource translates them into a `RequestSpec`.

### 3.1 Sealed types for mutually-exclusive `chain` groups

`ExpirationFilter` (`OnDate`/`Dte`/`Between`/`MonthYear`/`All`) and `StrikeFilter` (`Exact`/`Range`/`Comparison` + `Operator`) are sealed, so "pick one variant" is compiler-enforced. Factory methods validate at creation. Pair constraints the type system can't express (`minBid ≤ maxBid`) stay as runtime checks in `OptionsChainRequest.build()`.

> Reviewer note: this is the pattern future resources copy. This is the PR to push back on it.

---

## 4. Chain request → query translation

`chain` is the densest mapping: ~25 optional params → query string, via `applyChainParams` (`OptionsResource.java`), called from `chainSpec` / `chainAsync`.

- Flat `if (r.foo() != null) b.query("foo", …)` for independent params, plus two sealed-type delegations:
  - `applyExpirationFilter` → `OnDate`→`?expiration=YYYY-MM-DD`, `Dte`→`?dte=N`, `Between`→`?from=…&to=…`, `MonthYear`→`?month=M&year=YYYY`, `All`→`?expiration=all`. **Check the trailing `else { throw IllegalStateException }`** — the `instanceof` chain isn't compiler-exhaustive; the guard fails loudly if a future variant is added without a branch (unreachable today, won't show in coverage — by design).
  - `strikeFilterWireValue` → `?strike=` value: `150` / `140-160` / `>150` (no trailing zeros via `formatStrike`).

What to verify:
- Every `OptionsChainRequest` getter has a line in `applyChainParams` (a param read nowhere silently does nothing).
- `minBid`/`maxBid`/`minAsk`/`maxAsk` are real backend params (verified against the handler).

The `chainExpirationFilter*` / `chainStrikeFilter*` / per-param URL unit tests assert the exact query string per branch.

---

## 5. The option-row deserializer: nullable fields + columns + Option A

**This is the correctness-critical section.** `quotes` and `chain` emit the same per-contract parallel-arrays row, so they share `optionRowsDeserializer(wrapper)` (`OptionsResource.java`), registered for both `OptionsQuotes` and `OptionsChain` in `wireFormatModule()`.

### 5.1 Every field is nullable

`OptionQuote` is now **all `@Nullable` boxed types** — including the structural ones (`strike`, `bid`, `side`, …), not just the greeks. This is what lets `columns` project the response to a subset: a field the consumer didn't request comes back `null`.

### 5.2 Lenient decode

`buildOptionRow` reads **every** column through an `OrNull` accessor (`textOrNull`/`dblOrNull`/`lngOrNull`/`boolOrNull`, dates via `nodeOrNull`). The deserializer calls `ParallelArrays.zip(p, root, List.of(), OPTION_ALL_FIELDS, …)` — note the **empty required-fields list**: every column is optional at the wire level, so `zip` never throws on an absent column.

`ParallelArrays.Row.cellOrNull(field)` is the primitive: `null` when the column isn't in the response map **or** the cell is null/missing; the `OrNull` accessors still throw `typeMismatch` on a present-but-wrong-type cell (leniency covers **absence, not corruption**).

### 5.3 Option A restores strictness — no silent failures

If every column is optional, how is "the backend dropped a field you asked for" detected? `validateRequestedColumns(p, root, rows, ctxt)`, run after decode:

- Reads the requested columns from the Jackson context attribute (`JsonResponseParser.REQUESTED_COLUMNS_ATTR`), which the resource sets to `config.columns()` via `parse(env, type, requestedColumns)`.
- For each field in `OPTION_REQUIRED_FIELDS` (the 20 structural columns — **not** `iv`/greeks): if it was requested (explicitly in `columns`, or implicitly because no `columns` filter was applied) and `!root.has(field)` → throw `JsonMappingException` → `ParseError`.

The resulting contract (the one to verify):

| Field | Requested? | In response? | Result |
|---|---|---|---|
| structural (`strike`, …) | yes (or no `columns`) | yes | value |
| structural | yes (or no `columns`) | **no** | 💥 `ParseError` (Option A) |
| structural | no (projected away via `columns`) | no | ✅ `null` |
| `iv` / greeks | — | no | ✅ `null` (legitimately optional) |

So a `null` a consumer sees means *only* "I projected it away" or "legitimately-optional model value" — never "a required field was silently dropped." **Strict-by-default is preserved** (no `columns` ⇒ all required columns implicitly requested ⇒ a missing one still `ParseError`s).

### 5.4 Greeks helpers

`OptionQuote.presentGreeks()` (`Set<Greek>`) and `greek(Greek)` (`@Nullable Double`) give a typed way to inspect which model values are present. `Greek` = `DELTA`/`GAMMA`/`THETA`/`VEGA`/`RHO`; **IV is not a greek** (read via `iv()`).

### 5.5 Tests that document this

`OptionsResourceTest`: `columnsProjectionDecodesRequestedAndNullsTheRest`, `columnsRequestedButOmittedByApiThrowsParseError`, `noColumnsFilterStillRequiresAllStructuralColumns`, `presentGreeksReportsNonNullGreeks`, plus the original `quoteDecodesNullModelValuesAsNull` (greeks null → null, not `ParseError`).

---

## 6. Universal parameters + the CSV/HTML facets

### 6.1 Universal params on the resource

`OptionsResource` is an **immutable configured value**: `dateFormat()/mode()/limit()/offset()/columns()` each return a configured copy carrying a `RequestConfig`, applied to every subsequent call (`config.applyTo(builder)`). So "configure once, call many" works, and the config carries into `.asCsv()`. The `universalParamsReachTheWire` test asserts they land on the query string.

The split matters: type-preserving params (`dateFormat`/`mode`/`limit`/`offset`) live on the typed resource; **`human`/`headers` live only on the CSV facet** (they reshape the output, so they don't cohere with the typed decode). `columns` is on both.

### 6.2 The facets

- `asCsv()` → `OptionsCsvResource` → `CsvResponse` (`values()`/`csv()` = raw CSV text). Reuses the package-private static `*Spec` builders on `OptionsResource`, sets `format=csv`, and skips the typed decode. **Omits `lookup`** (a scalar — no CSV). Fan-out mirrors the container: `quotes(...)` → `Map<String, CsvResponse>`.
- `asHtml()` → `OptionsHtmlResource`/`HtmlResponse` is **package-private (built, not exposed)** — the backend serves no HTML. `CsvResponse ≠ HtmlResponse` (distinct types). Verify `asHtmlFacetSendsFormatHtml` exercises it from the same package.

---

## 7. The `quotes` multi-symbol fan-out

The backend `quotes` endpoint takes a single `optionSymbol`. The SDK's `quotes(...)` accepts N symbols and fans out one request each.

- One `quoteSpec(symbol, date, from, to, countback)` per symbol, each dispatched through the normal transport (50-permit `AsyncSemaphore`, retry, preflight).
- Results into a `LinkedHashMap` keyed by the **input symbol** (insertion order preserved), each value a full `OptionsQuotesResponse` (so per-symbol `statusCode`/`isNoData`/`requestId`/`json` stay observable).
- **Fail-fast:** `allOf(...)` ⇒ the map's future completes exceptionally if any single request fails. Deliberate (a partial map would hide failures). The CSV facet mirrors the shape: `Map<String, CsvResponse>`.

Verify: `quotesFansOutToMultipleContracts` (integration) and the unit fan-out test.

---

## 8. lookup / expirations / strikes

Three simpler endpoints, each with a hand-written deserializer (their wire shapes don't fit the parallel-arrays row):

- **lookup** — flat `{"s":"ok","optionSymbol":"…"}` → `OptionsLookupResponse.values()` is the `String`. Path URL-encoded per-segment via `PathSegments.encode` (`/` preserved, space → `%20`, `$` encoded).
- **expirations** — parallel `expirations[]` + scalar `updated` → `values()` is `List<ZonedDateTime>`, `updated()` the timestamp (null on `no_data`).
- **strikes** — one top-level key **per expiration date** + `s`/`updated` → `values()` is `List<ExpirationStrikes>`. The deserializer is strict (unrecognized non-date key / non-numeric strike throws). Note: `columns`/Option A do not apply to these (they're not the option-row shape).

`MarketDataDates` handles all three `dateformat` variants (unix / ISO-string / spreadsheet) uniformly.

---

## 9. Subtle corners (finding-driven)

| # | Corner | What to know |
|---|---|---|
| 9.1 | **No silent failures (Option A)** | All fields nullable for `columns`, but `validateRequestedColumns` still `ParseError`s on a requested-but-missing **required** column. A `null` only means "projected away" or "optional model value." §5.3. |
| 9.2 | **HTTP 203 is success** | API returns `203 Non-Authoritative Information` for cached/delayed data; IT assert `200 || 203` so they don't flap with market hours. |
| 9.3 | **`expiration=all` ≠ no filter** | Omitting the expiration filter returns only the **front-month**; `all()` returns every expiration. Verified against the backend. |
| 9.4 | **`countback` validation** | Positive; mutually exclusive with `date` and `from` (pair with `to`). |
| 9.5 | **Exhaustiveness guard** | `applyExpirationFilter` `else throw` — sealed `instanceof` chains aren't compiler-exhaustive; the guard fails loudly on an unmatched future variant. |
| 9.6 | **`quotes` is fan-out, not bulk** | The backend path takes one symbol; the SDK fans out N concurrent calls. |
| 9.7 | **`human`/`headers` are CSV-only** | They reshape the output, which breaks the typed decode — so they live on the CSV facet, not the typed resource. The facet boundary coincides with the params' type-safety boundary. |
| 9.8 | **HTML built, not exposed** | `asHtml()` is package-private; the server serves no HTML yet. Enabling later is a one-line change. |
| 9.9 | **`source` intentionally absent** | Internal provider param, not in the public schema/requirements/Python SDK. |

---

## 10. Out of scope for this review

Do **not** flag as missing — deferred decisions, documented in [`PR.md`](../PR.md):

- **HTML facet exposure** — built and tested; `asHtml()` stays package-private until the backend serves `format=html`.
- **§13 JaCoCo 100% threshold** — deferred until the full resource layer lands.
- **§8 per-response rate-limit snapshot** — still client-level via `client.getRateLimits()`.
- **`stocks`/`funds`/`markets`** — adopt this convention next.

(§3 universal parameters are **no longer** deferred — they're implemented here, §6.)

---

## Reviewer checklist

- [ ] Response model: every endpoint returns a named `MarketDataResponse<T>`; `values()` is the flat payload; metadata surface complete; `Response<T>` fully removed; `utilities` migrated with no regression.
- [ ] Request convention: Builder request per endpoint, no `String` overloads; required args non-optional; cross-field validation in `build()`.
- [ ] Sealed `ExpirationFilter`/`StrikeFilter` translate every variant in `applyChainParams`, with the `else throw` guard; `applyChainParams` reads **every** getter.
- [ ] Nullable + Option A: all `OptionQuote` fields `@Nullable`; `buildOptionRow` lenient; `validateRequestedColumns` fails on requested-but-missing required columns; strict-by-default preserved.
- [ ] `ParallelArrays` OrNull accessors are additive; strict path unchanged; an absent optional column can't throw.
- [ ] Universal params reach the wire; `human`/`headers` only on the CSV facet; `columns` projection round-trips.
- [ ] Facets: `asCsv()` returns `CsvResponse`, omits `lookup`, fan-out → `Map<String, CsvResponse>`; `asHtml()` package-private; `CsvResponse ≠ HtmlResponse`.
- [ ] `quotes` fan-out: per-symbol map, insertion order, fail-fast acceptable.
- [ ] Unit (`./gradlew build`) and integration (`MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest`) both green; `make demo-options` runs against the mock server.
- [ ] Deferred items (§10) understood and not blocking.
