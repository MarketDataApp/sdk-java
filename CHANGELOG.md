# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Default retry attempts corrected from 3 to 4 (one initial + three retries) to
  match SDK requirements §9.3 ("max 3 retries, yielding 4 total attempts").

### Added
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
