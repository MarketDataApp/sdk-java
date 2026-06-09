# Stocks Review Guide — `11_stocks_resource`

This guide walks a reviewer through the `stocks` resource added on the `11_stocks_resource` branch. It is organized by **flow**, not by file.

This PR **adopts the conventions the [`options` PR](OPTIONS_REVIEW_GUIDE.md) established** — the `MarketDataResponse<T>` + named-response model, the Builder-based per-endpoint request, nullable fields + `columns` + Option A, and the CSV/HTML facets — and applies them to stocks. The shared layers (transport, retry, rate-limit parsing, `ParallelArrays`, `JsonResponseParser`) are reused unchanged; there are **two additive shared-layer changes** — a tolerant date parser (`MarketDataDates.parseDateOrTimestampField`) and a per-response rate-limit accessor (`MarketDataResponse.rateLimit()`, §8.2). If you reviewed the options PR, the load-bearing shape will be familiar — focus your time on §4 (per-endpoint query translation), §5 (the per-endpoint required-column sets + the `news` deserializer), §7–§8 (batch vs. fan-out, news/earnings specifics), and the two stocks-/SDK-specific additions: **§12 candle auto-chunking** (§9.9) and **§8.2 per-response rate limits** (§9.10).

Suggested reading order: §1 (what's here) → §5 (deserializers: nullable + columns + Option A) → §3/§4 (requests + query translation) → §7 (batch) → §8 (news/earnings) → §9 (subtle corners). ~30 minutes.

`file:line` citations target `HEAD` on this branch; line numbers drift — if one looks off, search for the symbol it names.

## Table of contents

- [Running it locally](#running-it-locally)
1. [What this PR adds](#1-what-this-pr-adds)
2. [The response model (reused)](#2-the-response-model-reused)
3. [Requests + the `StockResolution` value type](#3-requests--the-stockresolution-value-type)
4. [Request → query translation, per endpoint](#4-request--query-translation-per-endpoint)
5. [The row deserializers: nullable + columns + Option A](#5-the-row-deserializers-nullable--columns--option-a)
6. [Universal parameters + the CSV/HTML facets](#6-universal-parameters--the-csvhtml-facets)
7. [`quotes` / `prices`: batch, not fan-out](#7-quotes--prices-batch-not-fan-out)
8. [news + earnings specifics](#8-news--earnings-specifics)
9. [Subtle corners (wire-format-driven)](#9-subtle-corners-wire-format-driven)
10. [Out of scope for this review](#10-out-of-scope-for-this-review)
- [Reviewer checklist](#reviewer-checklist)

---

## Running it locally

```bash
make build                 # unit tests + Spotless + JaCoCo (JDK 17)

# Integration tests hit the live API (gated). A token in .env or the env is required:
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest

# Full stocks demo against the mock server (every endpoint + all params, CSV facet,
# columns projection, Option A). Needs the mock server up:
make publish && make mock-server   # (in one terminal)
make demo-stocks                   # (in another)   — or: ./gradlew -p examples/consumer-test runStocks
```

`StocksIntegrationTest` (shape assertions, AAPL/MSFT) runs against `api.marketdata.app`. `StocksApp` (`examples/consumer-test`) scripts the mock server's responses to demonstrate the columns/Option-A scenarios — it was run green end-to-end (`make demo-stocks`).

---

## 1. What this PR adds

### 1.1 Public API surface (new)

```
com.marketdata.sdk.StocksResource              (returned from client.stocks())
com.marketdata.sdk.StocksCsvResource           (returned from .asCsv())
com.marketdata.sdk.Stock{Candles,Quotes,Prices,News,Earnings}Response   (named per-endpoint responses)

com.marketdata.sdk.stocks.Stock{Candles,Quote,Quotes,Prices,News,Earnings}Request
com.marketdata.sdk.stocks.{StockCandle, StockQuote, StockPrice, StockNewsArticle, StockEarning}   (row records)
com.marketdata.sdk.stocks.StockResolution      (candle-resolution value type)
```

`StocksResource` (and `StocksCsvResource`) are `public final` with **package-private constructors** (ADR-007) — reached through `client.stocks()` / `.asCsv()`. Response wrapper types live in the **root** package (so façades construct them via package-private constructors); request/row records live in the public `com.marketdata.sdk.stocks` subpackage. `StockQuotesResponse` is shared by `quote` and `quotes` (same row shape).

### 1.2 Files to review, by role

| Area | Files | What to check |
|---|---|---|
| Resource façade | `StocksResource.java` | universal-param config, per-endpoint `*Spec` builders, the generic row deserializer + Option A, `asCsv()`/`asHtml()` |
| CSV/HTML facets | `StocksCsvResource.java`, `StocksHtmlResource.java` | reuse of the static `*Spec` builders, `format=csv/html` |
| Responses | `Stock*Response.java`, `MarketDataResponse.java`, `AbstractMarketDataResponse.java` | `values()` payload typing; `StockNewsResponse.updated()` scalar accessor; the new `rateLimit()` (§8.2) on the interface + base |
| Candle chunking | `StocksResource.candleChunks`/`candlesAsync`, `StockResolution.isIntraday`, `StocksCsvResource.mergeCsvBodies` | §12 intraday split → concurrent fetch → merge |
| Requests | `stocks/Stock*Request.java`, `StockRequests.java`, `StockResolution.java` | Builder validation, shared `validateWindow`, the value-type resolution |
| Row records | `stocks/StockCandle/StockQuote/StockPrice/StockNewsArticle/StockEarning.java` | `@Nullable` fields; `StockNewsArticle` non-null (always emitted) |
| News deserializer | `StockNewsDeserializer.java` | per-row arrays + scalar `updated`; envelope handling |
| Reused infra (changed) | `MarketDataDates.java`, `MarketDataResponse.java`, `AbstractMarketDataResponse.java` | additive only: `parseDateOrTimestampField`; `rateLimit()` |
| Wiring | `MarketDataClient.java` | `client.stocks()` |

### 1.3 What changed in shared layers (additive only)

- **`MarketDataDates`** gained `parseDateOrTimestampField` — the existing `parseDateField` / `parseTimestampField` are untouched. Confirm no existing parser was modified.
- **`MarketDataResponse`** gained `rateLimit()` (§8.2), implemented once in `AbstractMarketDataResponse` (it parses `RateLimitHeaders.parse(envelope.headers())` in the constructor). Since that base is the **only** implementor of the interface, every existing response type — options, utilities, `CsvResponse`, `HtmlResponse` — gets it with no per-type change. Confirm the interface addition didn't miss an implementor and that options/utilities tests still pass.
- Everything else (`ParallelArrays`, `JsonResponseParser`, `RequestConfig`, `RateLimitHeaders`, transport/retry/status-cache/exceptions) is reused exactly as the options PR left it.

---

## 2. The response model (reused)

No new model concepts — `Stock*Response` are thin subclasses of `AbstractMarketDataResponse<T>` (see [Options guide §2](OPTIONS_REVIEW_GUIDE.md#2-the-response-model) for the full shape). `values()` is the flat payload per endpoint (`List<StockCandle>`, `List<StockQuote>`, …). The one endpoint extra: `StockNewsResponse.updated()` exposes the feed's scalar update time (it sits at the response root, not on each row).

**New SDK-wide accessor:** `MarketDataResponse.rateLimit()` returns the `RateLimitSnapshot` parsed from this response's own `x-api-ratelimit-*` headers (§8.2) — request-scoped, distinct from the client-level `client.getRateLimits()`. `null` when the four headers weren't all present. §9.10.

What to check: `values()` return types match the wire shape per endpoint; `updated()` is only on `StockNewsResponse`; `rateLimit()` is populated from the per-response headers (the merged-chunk candle response reflects the final slice's headers, §9.9).

---

## 3. Requests + the `StockResolution` value type

Same convention as options: **one Builder-based request per endpoint, no `String` overloads.** `of(...)` for the no-optionals path, `builder(...)…build()` otherwise.

- **`StockResolution` is a value type, not an enum** (`StockResolution.java`). The API accepts an open-ended family of resolutions (any minute count, any multiple of hours/days/…), so an enum can't enumerate them. Constants `DAILY`/`WEEKLY`/`MONTHLY`/`YEARLY` + factories `minutes/hours/days/weeks/months/years` (all reject non-positive) + `of(String)` for an arbitrary token. `wireValue()` is the path segment. `equals`/`hashCode` by wire value.
- **Shared window validation** (`StockRequests.validateWindow`, used by candles/news/earnings): `date` is single-point and mutually exclusive with `from`/`to`/`countback`; `countback` is positive and pairs with `to` (not `from`). Same rule the options requests use.
- The quote requests carry the boolean flags `extended` / `candle` / `week52`; `candle`/`week52` opt the OHLC / 52-week columns into the response.

---

## 4. Request → query translation, per endpoint

All in `StocksResource.java` as package-private static `*Spec` builders (reused by the CSV/HTML facets):

| Endpoint | Path | Params |
|---|---|---|
| `candlesSpec` | `stocks/candles/{resolution}/{symbol}` | `date`/`from`/`to`/`countback`, `exchange`, `extended`, `country`, `adjustsplits`, `adjustdividends` |
| `quoteSpec` | `stocks/quotes/{symbol}` | `extended`, `candle`, `52week` |
| `quotesSpec` | `stocks/quotes` | `symbols=A,B,C` (comma-joined), then the quote flags |
| `pricesSpec` | `stocks/prices` | `symbols=A,B,C` |
| `newsSpec` | `stocks/news/{symbol}` | window (`date`/`from`/`to`/`countback`) |
| `earningsSpec` | `stocks/earnings/{symbol}` | window + `report` (e.g. `2024-Q3`) |

What to verify:
- Path segments encoded via `PathSegments.encode` (the resolution token too).
- The wire param name for the 52-week flag is **`52week`** (not `week52` — that's the Java builder name); OHLC opt-in is **`candle`**.
- `symbols` is comma-joined then URL-encoded by the transport (`,` → `%2C`); the backend splits after decode. Batched endpoints get a trailing slash before `?` (`/v1/stocks/quotes/?symbols=…`).
- Dates are ISO via `DateTimeFormatter.ISO_LOCAL_DATE`.

---

## 5. The row deserializers: nullable + columns + Option A

**The correctness-critical section.** candles/quotes/prices/earnings share one generic factory; news is hand-written.

### 5.1 The generic parallel-arrays deserializer

`rowsDeserializer(allFields, requiredFields, rowBuilder, wrapper)` (`StocksResource.java`) is the stocks analog of options' `optionRowsDeserializer`:

- Calls `ParallelArrays.zip(p, root, List.of(), allFields, rowBuilder)` — **empty required list**, every column optional at the wire level, so a `columns` projection never throws on an absent column.
- The row builders (`buildCandleRow`/`buildQuoteRow`/`buildPriceRow`/`buildEarningRow`) read **every** column through an `OrNull` accessor; dates go through `timestampOrNull` (→ `parseTimestampField`) or `dateOrTimestampOrNull` (→ `parseDateOrTimestampField`, §9).
- Then `validateRequestedColumns(p, root, rowCount, ctxt, requiredFields)` restores strictness (Option A): for each **required** field, if it was requested (explicitly via `columns`, or implicitly because no `columns` filter was applied) and `!root.has(field)` → `ParseError`.

### 5.2 The per-endpoint required-column sets

This is the stocks-specific judgement call to review — which columns are "required" (Option A guards them) vs. legitimately nullable:

| Endpoint | `*_FIELDS` (all, optional at wire) | Required (Option-A-guarded) | Legitimately nullable |
|---|---|---|---|
| candles | `t,o,h,l,c,v` | **all** | — |
| quotes | the 11 standard + `o,h,l,c,52weekHigh,52weekLow` | the **11 standard** (`symbol,ask,askSize,bid,bidSize,mid,last,change,changepct,volume,updated`) | OHLC + 52-week (opt-in via `candle`/`week52`) |
| prices | `symbol,mid,change,changepct,updated` | **all** | — |
| earnings | 12 columns | `symbol,date,updated` | `fiscalYear`/`fiscalQuarter`/`reportDate`/`reportTime`/EPS figures (null on forward-quarter / fundamentals-missing rows) |

> Reviewer note: the quote standard columns are always emitted by the backend (it null-fills them when the market is closed), so they're "required" structurally even though their **values** are nullable — `NaN→null` decodes to `null` without tripping Option A (the column key is present). Confirm the earnings required-set is minimal enough that a forward-quarter row doesn't `ParseError` (regression test `earningsToleratesNullFutureQuarterFields`).

### 5.3 The `news` deserializer

`StockNewsDeserializer` is hand-written because the shape is mixed: per-row article arrays **plus a scalar `updated` at the root** (which `ParallelArrays.listDeserializer` can't express). Article columns are read **strictly** (`row.text(...)`) — the backend always emits them for a real article row; `publicationDate` via `parseDateOrTimestampField`. `updated` is read only when present (omitted for date-bounded historical queries → `null`). Envelope handling (`error`/`no_data`) mirrors `ParallelArrays`.

### 5.4 Tests that document this

`StocksResourceTest`: `columnsProjectionDecodesRequestedAndNullsTheRest`, `columnsRequestedButOmittedByApiThrowsParseError`, `noColumnsFilterStillRequiresAllStructuralColumns`, `quoteNullNumericCellsDecodeToNull`, `earningsToleratesNullFutureQuarterFields`, `newsHistoricalQueryOmitsUpdated`.

---

## 6. Universal parameters + the CSV/HTML facets

Identical mechanics to options:

- `StocksResource` is an **immutable configured value**: `dateFormat()/mode()/limit()/offset()/columns()` each return a configured copy carrying a `RequestConfig`, applied to every call. `universalParamsReachTheWire` asserts they land on the query string.
- `asCsv()` → `StocksCsvResource` → `CsvResponse`; reuses the static `*Spec` builders, sets `format=csv`, adds the output-shaping `human`/`headers` (CSV-only). Every endpoint is covered (no scalar to omit, unlike options' `lookup`).
- `asHtml()` → `StocksHtmlResource`/`HtmlResponse` is **package-private (built, not exposed)** — the backend serves no HTML.

> Note: these per-resource setters are intentional copy-paste of the options ones; SDK-wide de-duplication into a self-typed base is a tracked pre-v1 refactor, not part of this PR.

---

## 7. `quotes` / `prices`: batch, not fan-out

**The key contrast with options.** `options.quotes` fans out one request per contract (the backend path is single-symbol) and returns `Map<String, OptionsQuotesResponse>`. The stocks backend accepts a **comma list in a single request**, so:

- `stocks.quotes` / `stocks.prices` send **one** request (`?symbols=A,B,C`) and return a **single** `StockQuotesResponse` / `StockPricesResponse` with one row per symbol.
- No fan-out, no map, no `AsyncSemaphore` fan-out logic — simpler and one round-trip.

Verify: `quotesBatchesSymbolsInOneRequest` / `pricesBatchesSymbolsInOneRequest` assert exactly one captured request and the multi-row response.

---

## 8. news + earnings specifics

- **news** — `StockNewsResponse.values()` is the articles; `updated()` is the feed-level scalar (null for date-bounded queries). §5.3.
- **earnings** — both historical reports and the forward calendar share `StockEarning`. The forward-quarter rows carry null fundamentals (`fiscalYear`/`fiscalQuarter`) and null report fields (`reportDate`/`reportTime`/EPS) — modeled `@Nullable` and Option-A-excluded (§5.2). `fiscalYear`/`fiscalQuarter` are `@Nullable Integer` (read via `lngOrNull` → `intValue`).

---

## 9. Subtle corners (wire-format-driven)

| # | Corner | What to know |
|---|---|---|
| 9.1 | **`parseDateOrTimestampField`** | A **daily** candle's `t` (and earnings `date`/`reportDate`, news `publicationDate`) come back **date-only** (`"2025-01-17"`) under `dateformat=timestamp`, while an **intraday** candle's `t` is a full timestamp. The new parser tries the full-timestamp format, falls back to date-only (→ market-zone midnight), then numeric (unix/spreadsheet). `updated` fields use the plain `parseTimestampField` (always a full timestamp). |
| 9.2 | **`NaN→null` over numerics** | The backend nulls quote/price numeric columns for a closed/illiquid market — so those fields are `@Nullable` and decode to `null` (the column key is still present, so Option A doesn't fire). |
| 9.3 | **opt-in columns** | Quote OHLC (`o/h/l/c`) appears only with `candle=true`; 52-week with `52week=true`. Absent otherwise → `null`, not an error (they're in the all-fields list but not the required set). |
| 9.4 | **batch ≠ fan-out** | `stocks.quotes`/`prices` are a single comma-list request (§7). Do not expect a per-symbol map. |
| 9.5 | **HTTP 203 is success** | Cached/delayed data returns `203`; IT assert `200 || 203` so they don't flap with market hours. |
| 9.6 | **`StockResolution` is a value type** | Not an enum — the API's resolution family is open-ended. Factories validate positivity; `of(String)` passes an arbitrary token through. |
| 9.7 | **wire vs. Java names** | Builder `week52(...)` → wire `52week`; builder `candle(...)` → wire `candle`; `adjustSplits`/`adjustDividends` → wire `adjustsplits`/`adjustdividends`. |
| 9.8 | **`mode=cached` is quote-only** | The backend rejects `cached` on list endpoints (candles/news/earnings) — a consumer concern, not enforced by the SDK. |
| 9.9 | **Candle auto-chunking (§12)** | An intraday request with a `from` bound spanning > ~1 year is split into year-sized sub-requests (`candleChunks`), fanned out through the 50-permit pool and merged (`candlesAsync` typed; `StocksCsvResource.mergeCsvBodies` for CSV). `StockResolution.isIntraday()` is the trigger. When split, the merged response's metadata (status/json/`rateLimit`) reflects the final slice. |
| 9.10 | **Per-response rate limit (§8.2)** | `MarketDataResponse.rateLimit()` is parsed from each response's own `x-api-ratelimit-*` headers (in `AbstractMarketDataResponse`, SDK-wide) — request-scoped, distinct from client-level `getRateLimits()`. |

---

## 10. Out of scope for this review

Do **not** flag as missing — deferred, documented in [`PR.md`](../PR.md):

- **ADR-008 accept + ADR-009**, and the `docs/java-sdk-requirements.md` per-resource section that depends on an accepted source ADR.
- **HTML facet exposure** — package-private until the backend serves `format=html`.
- **SDK-wide setter de-duplication** — the universal-param setters are copy-pasted per resource; a self-typed-base refactor is tracked for before v1.
- **§13 JaCoCo 100% threshold** — unchanged from the options PR.
- **`funds`/`markets`** — adopt this convention next.

---

## Reviewer checklist

- [ ] Shared layers changed only additively: `MarketDataDates.parseDateOrTimestampField` and `MarketDataResponse.rateLimit()` (implemented once in `AbstractMarketDataResponse`); no existing parser modified; options/utilities responses inherit `rateLimit()` with no per-type change.
- [ ] Requests: Builder per endpoint, no `String` overloads; `StockResolution` value-type factories validate; shared `validateWindow` (date XOR range, countback pairs with `to`).
- [ ] Query translation: every `*Spec` reads every getter; wire names correct (`52week`, `candle`, `adjustsplits`, …); `symbols` comma-joined; paths encoded.
- [ ] Nullable + Option A: every row field `@Nullable`; row builders lenient; per-endpoint **required-column sets** are right (quote 11-standard required, OHLC/52-week optional; earnings only symbol/date/updated required); strict-by-default preserved.
- [ ] `news` deserializer: per-row arrays + scalar `updated` (null on date-bounded); article fields strict; envelope handling.
- [ ] `quotes`/`prices` are a single batched request (one response, N rows) — not a fan-out map.
- [ ] Facets: `asCsv()` covers every endpoint, returns `CsvResponse`, adds `human`/`headers`; `asHtml()` package-private.
- [ ] Date handling: daily date-only vs. intraday full timestamp both decode; `updated` uses the plain timestamp parser.
- [ ] §12 candle chunking: `isIntraday()` classifier correct; only intraday + `from`-bounded ranges split; slices contiguous/non-overlapping (`to` exclusive); concurrent via the 50-permit pool; merged in chronological order; non-intraday / no-`from` stay single-request; CSV path merges with header dedup.
- [ ] §8.2 per-response rate limit: `rateLimit()` parsed from each response's headers; `null` when absent; request-scoped (distinct from `getRateLimits()`).
- [ ] Unit (`./gradlew build`, 728 tests) and integration (`MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest`) green; `make demo-stocks` runs against the mock server.
- [ ] Deferred items (§10) understood and not blocking.
