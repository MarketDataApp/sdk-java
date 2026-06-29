# Markets Review Guide — `13_market_resource`

This guide walks a reviewer through the `markets` resource added on the `13_market_resource` branch. It is organized by **flow**, not by file.

This PR closes out the resource series: it **adopts the conventions the [`options`](OPTIONS_REVIEW_GUIDE.md) and [`stocks`](STOCKS_REVIEW_GUIDE.md) PRs established** — `MarketDataResponse<T>` + named responses, the Builder-based per-endpoint request, nullable fields + `columns` + Option A, the CSV/HTML facets — and applies them to the single markets endpoint, `GET /v1/markets/status/`. **No shared-layer changes at all**: transport, retry, rate-limit parsing, `ParallelArrays`, `JsonResponseParser`, `MarketDataDates`, and `RequestConfig` are reused untouched.

If you reviewed the stocks PR, the only genuinely new content is the markets-specific semantics (§3) and the all-optional parameter surface (§4). ~10 minutes.

Suggested reading order: §1 (what's here) → §3 (markets-specific semantics) → §4 (request + query translation) → §5 (deserializer). `file:line` citations target `HEAD` on this branch.

## Table of contents

- [Running it locally](#running-it-locally)
1. [What this PR adds](#1-what-this-pr-adds)
2. [The response model (reused)](#2-the-response-model-reused)
3. [Markets-specific semantics](#3-markets-specific-semantics)
4. [Request → query translation](#4-request--query-translation)
5. [The row deserializer: nullable + columns + Option A](#5-the-row-deserializer-nullable--columns--option-a)
6. [Universal parameters + the CSV/HTML facets](#6-universal-parameters--the-csvhtml-facets)
- [Reviewer checklist](#reviewer-checklist)

---

## Running it locally

```bash
make build                 # unit tests + Spotless + JaCoCo (JDK 17)

# Integration tests hit the live API (gated). A token in .env or the env is required:
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest

# Full markets demo against the mock server (all params, null status cells, CSV facet,
# columns projection, Option A). Needs the mock server up:
make publish && make mock-server   # (in one terminal)
make demo-markets                  # (in another)   — or: ./gradlew -p examples/consumer-test runMarkets
```

`MarketsIntegrationTest` (shape assertions over a one-week window) runs against `api.marketdata.app`. `MarketsApp` (`examples/consumer-test`) scripts the mock server's responses to demonstrate every scenario — it was run green end-to-end (`make demo-markets`).

---

## 1. What this PR adds

### 1.1 Public API surface (new)

```
com.marketdata.sdk.MarketsResource             (returned from client.markets())
com.marketdata.sdk.MarketsCsvResource          (returned from .asCsv())
com.marketdata.sdk.MarketStatusResponse        (named response)

com.marketdata.sdk.markets.MarketStatusRequest (Builder-based request — every param optional)
com.marketdata.sdk.markets.MarketStatus        (row record: date/status + isOpen()/isClosed())
```

Same packaging rules as options/stocks (ADR-007): façades `public final` with package-private constructors in the **root** package; request/row records in the public `com.marketdata.sdk.markets` subpackage (`@NullMarked` via `package-info.java`); `MarketsHtmlResource` built but package-private (`asHtml()` stays hidden until the backend serves HTML). ADR-007 named `MarketsResource` as its canonical example — this PR makes that example real.

### 1.2 Files to review, by role

| Area | Files | What to check |
|---|---|---|
| Resource façade | `MarketsResource.java` | universal-param config, `statusSpec`, the row deserializer + Option A, `asCsv()`/`asHtml()` |
| CSV/HTML facets | `MarketsCsvResource.java`, `MarketsHtmlResource.java` | reuse of the static `statusSpec`, `format=csv/html` |
| Response | `MarketStatusResponse.java` | thin subclass of `AbstractMarketDataResponse<List<MarketStatus>>` |
| Requests | `markets/MarketStatusRequest.java`, `MarketRequests.java` | Builder validation, window rules, the no-required-args `of()` |
| Row record | `markets/MarketStatus.java` | `@Nullable` fields; `isOpen()`/`isClosed()` predicates |
| Wiring | `MarketDataClient.java` | `client.markets()` |
| Demos | `examples/.../MarketsApp.java`, `QuickstartApp.java` | mock-server walk-through; quickstart section enabled |

---

## 2. The response model (reused)

No new model concepts. `MarketStatusResponse` is a thin subclass of `AbstractMarketDataResponse<T>`; `values()` is `List<MarketStatus>` — one row per calendar day. Per-response `rateLimit()` (§8.2) and the full `MarketDataResponse` surface come from the base for free.

---

## 3. Markets-specific semantics

The load-bearing review points — each is a *contract* fact, verified against the backend (`api/marketDataApi/markets/` + `common/util/markets_helper.py`) and the Python SDK:

1. **This is the exchange calendar, not API health.** `markets.status` answers "was/is the market open on these days?" from the `MarketHoliday` table; `utilities().status()` reports the API's *own* per-service health from the unversioned `/status/` route. The class javadocs call the distinction out on both `MarketsResource` and `MarketDataClient.markets()`. (No interaction with the §9.5 `StatusCache` either — that cache keys on the unversioned `/status/` path, which `/v1/markets/status/` is not.)
2. **Every parameter is optional.** A bare `MarketStatusRequest.of()` returns today's status for the US calendar — this is the only request type in the SDK with a no-args `of()`. Window shapes: `date` (single day) XOR `from`/`to` (inclusive range) XOR `to`+`countback`.
3. **`status` cells can be null.** The backend's holiday data is bounded; days outside its coverage come back as a **null cell in a present column** — so Option A is satisfied and the row decodes with `status() == null` (`isOpen()`/`isClosed()` both false). A null status means "the calendar has no answer", never a decode failure. Test: `statusNullCellsOutsideCalendarCoverageDecodeToNull`.
4. **`country` is pass-through.** Two-digit ISO 3166; the backend currently serves US only and answers `no_data` (404 + `{"s":"no_data"}`, which the SDK surfaces as a successful empty response) for anything else — we don't second-guess that server-side rule client-side.
5. **`isOpen()`/`isClosed()` are derived predicates** on the row record (`"open".equals(status)` / `"closed".equals(status)`), not stored fields — the wire value stays exposed verbatim via `status()`.

**Python-SDK parity:** `sdk-py` exposes `country/date/from_date/to_date/countback` — this PR exposes exactly the same five, same window validation (`from > to` rejected; here additionally `date` XOR range and `countback`-pairs-with-`to`, the same client-side rules stocks/funds apply).

---

## 4. Request → query translation

One spec builder, `MarketsResource.statusSpec` (package-private static, reused by both facets):

| Endpoint | Path | Params |
|---|---|---|
| `statusSpec` | `markets/status` | `country`, `date`/`from`/`to`/`countback` |

What to verify:
- No path parameters — everything is a query param; dates ISO-formatted (`2025-01-17`).
- Window rules in `MarketRequests.validateWindow` (same as stocks/funds): `date` mutually exclusive with `from`/`to`/`countback`; `countback` positive, pairs with `to` not `from` (the backend silently *ignores* countback when `from` is present — we reject the combination instead of silently dropping one side).
- The bare request produces `/v1/markets/status/` with no query string.

---

## 5. The row deserializer: nullable + columns + Option A

Identical mechanics to stocks/funds (see [Stocks guide §5](STOCKS_REVIEW_GUIDE.md#5-the-row-deserializers-nullable--columns--option-a)): the `rowsDeserializer`/`validateRequestedColumns` pair is **copied per resource** (the agreed pre-v1 dedup refactor folds these together with the universal-param setters).

- `STATUS_FIELDS = [date, status]` — both **required** (either may be projected away via `columns`). Note the asymmetry with §3.3: a missing `status` *column* is an Option A anomaly; a null `status` *cell* is data.
- `date` decodes through the tolerant `MarketDataDates.parseDateOrTimestampField` — unix seconds by default, date-only strings (`"2025-01-17"`) under `dateformat=timestamp`, lifted to market-zone midnight (`America/New_York`).
- Envelope handling via `ParallelArrays.zip`: `"s":"error"` → `ParseError` carrying `errmsg`; `"s":"no_data"` → empty `values()`.

The wire module registers under the name `marketdata-markets` in `MarketsResource`'s client-facing constructor — same once-per-client pattern as the other resources.

---

## 6. Universal parameters + the CSV/HTML facets

Same shape as stocks/options/funds: `dateFormat`/`mode`/`limit`/`offset`/`columns` return configured copies of `MarketsResource` ("configure once, call many"; the config carries into `asCsv()`); the CSV facet adds the output-shaping `human`/`headers`. The known copy-paste of these setters across resources is tracked tech debt for the pre-v1 self-typed-base refactor — do not review it as accidental duplication.

---

## Reviewer checklist

- [ ] `client.markets().status(...)` hits `GET /v1/markets/status/` with every param translated (unit: `statusAttachesAllParams`, `statusAttachesDateAndCountbackWindows`) and a bare `of()` produces no query params (`statusHitsVersionedEndpointWithNoRequiredParams`)
- [ ] `date` + `status` are both required columns under Option A; a null `status` **cell** decodes to null (`statusNullCellsOutsideCalendarCoverageDecodeToNull`)
- [ ] `isOpen()`/`isClosed()` are derived from the verbatim wire value, both false on a null cell
- [ ] Sync + async parity (`status` / `statusAsync`) via `transport.joinSync` (ADR-006)
- [ ] CSV facet sends `format=csv` + shaping params; HTML facet stays package-private
- [ ] `no_data` → empty list; error envelope → `ParseError` with `errmsg`
- [ ] Per-response `rateLimit()` populated from the four `x-api-ratelimit-*` headers
- [ ] `MarketDataClient` wires `markets()` like the other resources (constructed before `StatusCache`, inside the partial-construction guard); javadoc disambiguates vs `utilities().status()`
- [ ] Demos: `make demo-markets` green; `QuickstartApp` markets section enabled
- [ ] Integration: `MarketsIntegrationTest` (one-week window: both statuses present; countback window) passes with a token
