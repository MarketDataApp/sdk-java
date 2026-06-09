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
- **Options resource** (`client.options()`) — all six endpoints, each in sync +
  async form: `lookup`, `expirations`, `strikes`, `quote` (single contract),
  `quotes` (multi-contract fan-out returning
  `Map<String, Response<OptionsQuotes>>`), and `chain`. Every endpoint takes a
  Builder-based per-endpoint request object (no `String` convenience overloads).
  The `chain` request models its mutually-exclusive expiration and strike groups
  as sealed types (`ExpirationFilter`, `StrikeFilter`) so the exclusivity is
  compiler-enforced. Covers the `rho` greek (decoded as an optional, nullable
  column — absent on some feeds), the `expiration=all` filter (the full chain
  vs. the default front-month), and the `countback` historical-window parameter
  (validated: positive, and mutually exclusive with `date`/`from`).
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
- `RateLimits` record exposed via `MarketDataClient.getRateLimits()`.
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
