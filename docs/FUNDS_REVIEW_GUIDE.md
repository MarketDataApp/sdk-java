# Funds Review Guide — `12_funds_resource`

This guide walks a reviewer through the `funds` resource added on the `12_funds_resource` branch. It is organized by **flow**, not by file.

This PR is the smallest of the resource series: it **adopts the conventions the [`options`](OPTIONS_REVIEW_GUIDE.md) and [`stocks`](STOCKS_REVIEW_GUIDE.md) PRs established** — `MarketDataResponse<T>` + named responses, the Builder-based per-endpoint request, nullable fields + `columns` + Option A, the CSV/HTML facets — and applies them to the single funds endpoint, `GET /v1/funds/candles/{resolution}/{symbol}/`. **No shared-layer changes at all**: transport, retry, rate-limit parsing, `ParallelArrays`, `JsonResponseParser`, `MarketDataDates`, and `RequestConfig` are reused untouched.

If you reviewed the stocks PR, the only genuinely new content is what funds *don't* have (§3) and the parameter surface choices (§4). ~10 minutes.

Suggested reading order: §1 (what's here) → §3 (the three deliberate absences) → §4 (request + query translation) → §5 (deserializer). `file:line` citations target `HEAD` on this branch.

## Table of contents

- [Running it locally](#running-it-locally)
1. [What this PR adds](#1-what-this-pr-adds)
2. [The response model (reused)](#2-the-response-model-reused)
3. [What funds deliberately do NOT have](#3-what-funds-deliberately-do-not-have)
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

# Full funds demo against the mock server (all params, CSV facet, columns projection,
# Option A, the no-chunking proof). Needs the mock server up:
make publish && make mock-server   # (in one terminal)
make demo-funds                    # (in another)   — or: ./gradlew -p examples/consumer-test runFunds
```

`FundsIntegrationTest` (shape assertions, VFINX) runs against `api.marketdata.app`. `FundsApp` (`examples/consumer-test`) scripts the mock server's responses to demonstrate every scenario — it was run green end-to-end (`make demo-funds`).

---

## 1. What this PR adds

### 1.1 Public API surface (new)

```
com.marketdata.sdk.FundsResource               (returned from client.funds())
com.marketdata.sdk.FundsCsvResource            (returned from .asCsv())
com.marketdata.sdk.FundCandlesResponse         (named response)

com.marketdata.sdk.funds.FundCandlesRequest    (Builder-based request)
com.marketdata.sdk.funds.FundCandle            (row record: time/open/high/low/close)
com.marketdata.sdk.funds.FundResolution        (candle-resolution value type, daily-and-up only)
```

Same packaging rules as options/stocks (ADR-007): façades `public final` with package-private constructors in the **root** package; request/row records in the public `com.marketdata.sdk.funds` subpackage (`@NullMarked` via `package-info.java`); `FundsHtmlResource` built but package-private (`asHtml()` stays hidden until the backend serves HTML).

### 1.2 Files to review, by role

| Area | Files | What to check |
|---|---|---|
| Resource façade | `FundsResource.java` | universal-param config, `candlesSpec`, the row deserializer + Option A, `asCsv()`/`asHtml()` |
| CSV/HTML facets | `FundsCsvResource.java`, `FundsHtmlResource.java` | reuse of the static `candlesSpec`, `format=csv/html`, **no chunking path** |
| Response | `FundCandlesResponse.java` | thin subclass of `AbstractMarketDataResponse<List<FundCandle>>` |
| Requests | `funds/FundCandlesRequest.java`, `FundRequests.java`, `FundResolution.java` | Builder validation, window rules, the daily-and-up resolution type |
| Row record | `funds/FundCandle.java` | `@Nullable` fields; **no volume** |
| Wiring | `MarketDataClient.java` | `client.funds()` |
| Demos | `examples/.../FundsApp.java`, `QuickstartApp.java` | mock-server walk-through; quickstart section enabled |

---

## 2. The response model (reused)

No new model concepts. `FundCandlesResponse` is a thin subclass of `AbstractMarketDataResponse<T>`; `values()` is `List<FundCandle>`. Per-response `rateLimit()` (§8.2) and the full `MarketDataResponse` surface come from the base for free.

---

## 3. What funds deliberately do NOT have

These are the load-bearing review points — each is a *contract* fact, verified against the backend (`api/marketDataApi/funds/`) and the Python SDK:

1. **No volume column.** Funds are NAV series; the backend never emits `v` (nor `vwap`/`n`). `FundCandle` is OHLC-only and `CANDLE_FIELDS` is `t,o,h,l,c`. A stocks-style candle body with `v` would still decode (unknown root fields are ignored by `ParallelArrays.zip`).
2. **No intraday resolutions.** The backend rejects minutely/hourly tokens with `"Intraday resolutions are not available for fund candles."` — so `FundResolution` offers only `DAILY`/`WEEKLY`/`MONTHLY`/`YEARLY` + `days/weeks/months/years(n)` factories (no `minutes`/`hours`, no `isIntraday()`). `of(String)` still passes arbitrary tokens through (value-type philosophy: client-side validation doesn't chase the server's grammar); a hand-built intraday token surfaces as the API's error envelope → `ParseError`.
3. **No §12 auto-chunking.** The ~one-year span cap that chunking works around only applies to intraday candle requests. A multi-decade daily funds request is **one** HTTP request, on both the typed resource and the CSV facet (which is why `FundsCsvResource` has no `mergeCsvBodies` analogue). Test: `candlesLongDailyRangeIsASingleRequest`.
4. **No `extended` parameter.** Extended-hours sessions only exist intraday; exposing the flag would be dead surface. (The backend's OpenAPI schema lists it because funds reuse the stock-candles schema helper, but it can never have an effect.)

Python-SDK parity note: `sdk-py` exposes `symbol/resolution/from/to/countback` only. This PR additionally exposes `date`, `exchange`, `country`, `adjustsplits`, `adjustdividends` — all accepted by the funds endpoint (shared candles serializer/handler) and all meaningful for daily-and-up fund series (CRSP split/dividend adjustment very much applies to funds).

---

## 4. Request → query translation

One spec builder, `FundsResource.candlesSpec` (package-private static, reused by both facets):

| Endpoint | Path | Params |
|---|---|---|
| `candlesSpec` | `funds/candles/{resolution}/{symbol}` | `date`/`from`/`to`/`countback`, `exchange`, `country`, `adjustsplits`, `adjustdividends` |

What to verify:
- Path segments encoded via `PathSegments.encode` (resolution token too); dates ISO-formatted (`2025-01-17`).
- Window rules in `FundRequests.validateWindow` (same as stocks): `date` mutually exclusive with `from`/`to`/`countback`; `countback` positive, pairs with `to` not `from`.
- `FundCandlesRequest.Builder` rejects empty symbol; `of(resolution, symbol)` is the no-optionals shortcut.

---

## 5. The row deserializer: nullable + columns + Option A

Identical mechanics to stocks (see [Stocks guide §5](STOCKS_REVIEW_GUIDE.md#5-the-row-deserializers-nullable--columns--option-a)): the `rowsDeserializer`/`validateRequestedColumns` pair is **copied per resource** (the agreed pre-v1 dedup refactor will fold these together with the universal-param setters).

- `CANDLE_FIELDS = [t, o, h, l, c]` — all five are **required** (any may be projected away via `columns`).
- `t` decodes through the tolerant `MarketDataDates.parseDateOrTimestampField` — under `dateformat=timestamp` the daily `t` comes back date-only (`"2025-01-17"`) and is lifted to market-zone midnight (`America/New_York`).
- Envelope handling via `ParallelArrays.zip`: `"s":"error"` → `ParseError` carrying `errmsg`; `"s":"no_data"` → empty `values()`.
- Option A: a *requested* (or not-projected-away) column the API omitted → `ParseError`, never a silent null.

The wire module registers under the name `marketdata-funds` in `FundsResource`'s client-facing constructor — same once-per-client pattern as the other resources (each registers its own `SimpleModule` on the shared `JsonResponseParser`).

---

## 6. Universal parameters + the CSV/HTML facets

Same shape as stocks/options: `dateFormat`/`mode`/`limit`/`offset`/`columns` return configured copies of `FundsResource` ("configure once, call many"; the config carries into `asCsv()`); the CSV facet adds the output-shaping `human`/`headers`. The known copy-paste of these setters across resources is tracked tech debt for the pre-v1 self-typed-base refactor — do not review it as accidental duplication.

---

## Reviewer checklist

- [ ] `client.funds().candles(...)` hits `GET /v1/funds/candles/{resolution}/{symbol}/` with every param translated (unit: `FundsResourceTest.candlesAttachesAllParams`, `candlesAttachesDateAndCountbackWindows`)
- [ ] `FundCandle` is OHLC-only (no volume) and all five wire columns are required under Option A
- [ ] `FundResolution` models daily-and-up only; no chunking anywhere (`candlesLongDailyRangeIsASingleRequest`)
- [ ] Sync + async parity (`candles` / `candlesAsync`) via `transport.joinSync` (ADR-006)
- [ ] CSV facet sends `format=csv` + shaping params; HTML facet stays package-private
- [ ] `no_data` → empty list; error envelope → `ParseError` with `errmsg`
- [ ] Per-response `rateLimit()` populated from the four `x-api-ratelimit-*` headers
- [ ] `MarketDataClient` wires `funds()` like the other resources (constructed before `StatusCache`, inside the partial-construction guard)
- [ ] Demos: `make demo-funds` green; `QuickstartApp` funds section enabled
- [ ] Integration: `FundsIntegrationTest` (VFINX, shape assertions) passes with a token
