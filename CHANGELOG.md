# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Default retry attempts corrected from 3 to 4 (one initial + three retries) to
  match SDK requirements §9.3 ("max 3 retries, yielding 4 total attempts").
- `.env` parser now strips trailing inline `# comment` markers (quote-aware: a
  `#` inside single/double quotes or adjacent to value chars stays part of the
  value). Previously a line like `MARKETDATA_TOKEN=abc # prod` produced the
  literal value `abc # prod`, which passes `validateApiKey` (printable ASCII)
  and surfaces later as a confusing `AuthenticationError` far from the .env
  source that caused it.
- `RequestHeaders` canonical constructor now rejects a `null` `headers` map
  with a clear `NullPointerException` naming the field, replacing the bare
  `Map.copyOf(null)` NPE that left consumers hunting for the offending
  argument. The wire-format deserializer additionally intercepts a top-level
  JSON `null` body via `JsonDeserializer#getNullValue` and surfaces it as a
  `ParseError` carrying the endpoint URL, status, and request id — preventing
  a malformed `/headers/` response from manifesting as an opaque NPE further
  down the call stack.

### Added
- **Stocks resource** (`client.stocks()`) — six endpoints, each in sync + async
  form: `candles`, `quote` (single symbol), `quotes` and `prices` (multi-symbol,
  batched into one request — one row per symbol, not a fan-out map like
  `options.quotes`), `news`, and `earnings`. Every endpoint takes a Builder-based
  per-endpoint request object. Candle resolution is a `StockResolution` value
  type (`DAILY`, `minutes(15)`, `hours(1)`, …) rather than an enum, since the API
  accepts an open-ended family of resolutions. Quote/price numeric fields are
  nullable (the backend nulls them for a closed/illiquid market); the OHLC and
  52-week columns are opt-in via `candle` / `week52`. `news` exposes the feed's
  scalar `updated()` off the response (distinct from each article's
  `publicationDate`); `earnings` tolerates the nullable fundamentals/report fields
  on synthesized forward-quarter rows. Mixed date/timestamp wire shapes (a daily
  candle's date-only `t` vs. an intraday full timestamp) decode uniformly. Carries
  the same universal-parameter setters, `columns` projection (with the Option A
  strict guarantee), and `asCsv()` facet as `options`. Intraday candle requests
  spanning more than ~one year are **auto-split** into year-sized sub-requests,
  fetched concurrently through the 50-permit pool and merged into one response
  (SDK requirements §12), on both the typed and CSV paths.
- **Per-response rate limits** — every `MarketDataResponse` now exposes
  `rateLimit()` returning a `RateLimitSnapshot` parsed from that response's own
  `x-api-ratelimit-*` headers (request-scoped, SDK requirements §8.2), distinct
  from the client-level `MarketDataClient.getRateLimits()`. Applies to every
  resource (options/utilities/stocks) and the CSV/HTML responses.
- **Options resource** (`client.options()`) — all six endpoints, each in sync +
  async form: `lookup`, `expirations`, `strikes`, `quote` (single contract),
  `quotes` (multi-contract fan-out returning a per-symbol
  `Map<String, OptionsQuotesResponse>`), and `chain`. Every endpoint takes a
  Builder-based per-endpoint request object (no `String` convenience overloads)
  and returns a named typed response (`OptionsChainResponse`,
  `OptionsLookupResponse`, …) implementing `MarketDataResponse<T>` — the payload
  is reached via `values()`. The `chain` request models its mutually-exclusive
  expiration and strike groups as sealed types (`ExpirationFilter`,
  `StrikeFilter`) so the exclusivity is compiler-enforced. Covers the `rho` greek
  (decoded as an optional, nullable column — absent on some feeds) plus the
  `Greek` enum with `presentGreeks()` / `greek(Greek)` accessors, the
  `expiration=all` filter (the full chain vs. the default front-month), and the
  `countback` historical-window parameter (validated: positive, and mutually
  exclusive with `date`/`from`). Universal parameters
  (`dateFormat`/`mode`/`limit`/`offset`) and `columns` projection are set fluently
  on the resource; a non-requested column decodes to `null`, while a required
  column you *did* request that the API omits raises a `ParseError` (Option A).
  The `asCsv()` facet returns CSV (`CsvResponse`) for every endpoint and adds the
  output-shaping `human` / `headers` params.
- Project scaffold per ADRs 001–007: Gradle Kotlin DSL build, JDK 17 toolchain,
  `integrationTest` source set, Spotless + JaCoCo, Vanniktech Maven Publish.
- `MarketDataClient` skeleton with two public constructors — a no-arg one
  for production (everything resolved from the cascade) and a 4-arg one
  (`apiKey`, `baseUrl`, `apiVersion`, `validateOnStartup`) for tests and
  short-lived runtimes. Default base URL (`https://api.marketdata.app`),
  default API version (`v1`), 99 s request / 2 s connect timeouts, HTTP/2,
  demo mode, `validateOnStartup` toggle, and a 50-permit concurrency
  semaphore (wiring lands with the request layer).
- Configuration cascade: explicit constructor parameters → `MARKETDATA_*`
  environment variables → `.env` file in CWD → built-in defaults.
- Sealed `MarketDataException` hierarchy with the seven canonical subtypes
  (`AuthenticationError`, `BadRequestError`, `NotFoundError`, `RateLimitError`,
  `ServerError`, `NetworkError`, `ParseError`), each carrying support context
  (`requestId`, `requestUrl`, `statusCode`, `timestamp`) and a
  `getSupportInfo()` helper.
- `RateLimitSnapshot` record exposed via `MarketDataClient.getRateLimits()`.
- JSpecify `@NullMarked` on every public package; JSpecify on `compileOnlyApi`
  so consumers get the annotations at compile time without a runtime dep.
- Token redaction utility (`Tokens`, package-private in the SDK root) for
  log output.
- MIT license; SDK version auto-detected from the JAR manifest
  (`Implementation-Version`).
- Single-package architecture per ADR-007: every infra class
  (`Configuration`, `EnvVars`, `Tokens`, `Version`) lives in
  `com.marketdata.sdk` as package-private. The `internal/` subpackage
  was removed; the consumer's compiler cannot reference these types,
  closing the "internal type leaks via constructor signature" gap that
  every non-modular Java SDK has.
