# feat: initial Market Data Java SDK scaffold + CI

> Branch `00_base_setup` → `main` · 2 commits · 39 files

Initial scaffold for the Java SDK. **No endpoints are implemented in this PR** — it lays the foundation that endpoints will land on. Subsequent PRs will add endpoints one by one, each shipping with its corresponding integration tests against the live API (gated by `MARKETDATA_RUN_INTEGRATION_TESTS=true`).

## What's included

- **Build**: Gradle 8.12 (Kotlin DSL), JDK 17 toolchain (`--release 17`), Spotless (Google Java Format), JaCoCo, Vanniktech Maven Publish, separate source set for integration tests.
- **Public API**: `MarketDataClient` with builder, shared HTTP/2 `HttpClient`, 50-permit concurrency semaphore, rate-limit accessor. `RateLimits` record. JSpecify `@NullMarked` across the entire public surface.
- **Exceptions**: sealed `MarketDataException` hierarchy with the 7 canonical subtypes (`AuthenticationError`, `BadRequestError`, `NotFoundError`, `RateLimitError`, `ServerError`, `NetworkError`, `ParseError`), each carrying support context (`requestId`, `requestUrl`, `statusCode`, `timestamp`) and a `getSupportInfo()` helper.
- **Configuration**: cascade `explicit → MARKETDATA_* env var → .env → default`. Defaults: `https://api.marketdata.app`, `v1`. Tokens are never logged verbatim (`Tokens.redact`).
- **CI** (two workflows):
  - `pull-request.yml`: PRs run on JDK 17 + coverage ratchet (cannot drop more than 5 pp below main).
  - `main.yml`: full matrix `{17, 21, 25}` and saves the coverage baseline that PRs compare against.
- **Docs**: `README.md`, `CLAUDE.md`, `CHANGELOG.md`, `LICENSE` (MIT).

## Tests & coverage

16 tests, all passing. Coverage: **83.1 % lines**, **98 % methods**, 100 % on the `exception` package.

## Architectural decisions

Follows ADRs 001–006 (already on `main`) and the foundational rules in the [cross-language SDK Requirements doc](https://www.marketdata.app/docs/sdk/sdk-requirements/) (§1, §4–§7, §10, §12, §15–§16). Request-flow specifics (retry, rate-limit header parsing, wire-format decoding, endpoints, integration tests) are intentionally deferred and listed explicitly in `CLAUDE.md`. They will land in the follow-up PRs alongside each endpoint.
