# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repo contains a working Java SDK in active development on branch `clean-architecture-restart`. Gradle 9.0 (Kotlin DSL) build per ADR-003; ~48 main + ~28 test source files; full CI matrix per ADR-002. The transport, retry, rate-limit, status-cache, and exception layers are wired; only the `utilities` resource façade is implemented today — stocks/options/funds/markets resources are still to come (see "Deliberately deferred" below).

Sibling repo: `../api/` is the backend (Python/Django). The Python SDK lives at `../../sdk-py/` (referenced from ADRs). The cross-language `sdk-requirements.md` is referenced as `../sdk-requirements.md` from inside `docs/`; it is canonical but not committed in this repo.

## How decisions get made here

The repo follows a strict **ADR-first** workflow:

1. A new architectural choice is captured as `docs/adr/ADR-NNN-*.md` and reviewed.
2. Once the ADR is **Accepted**, the corresponding section is added to `docs/java-sdk-requirements.md` with a citation back to the ADR.
3. New requirements should not be added to `java-sdk-requirements.md` without an accepted source ADR.

`docs/java-sdk-requirements.md` **supplements, not replaces**, the cross-language `sdk-requirements.md`. When the two conflict, the Java doc wins for the Java SDK only.

When asked to make architectural changes, prefer updating an existing ADR or proposing a new one (status `Proposed`) over silently editing requirements.

## Locked-in tech stack (ADRs 001–007, all Accepted)

These decisions are not up for debate without amending the corresponding ADR:

- **Java only.** Single artifact, no Kotlin sources. Published JAR must not bring `kotlin-stdlib` as a transitive dep. JSpecify is compile-time only and doesn't count. (ADR-001)
- **Kotlin consumers are first-class via interop, not via a Kotlin artifact.** A separate `marketdata-sdk-java-kotlin` extensions JAR (Option E) is deferred. (ADR-001)
- **JDK 17 minimum.** Build with `javac --release 17`; no multi-release JAR. CI test matrix is `{17, 21, 25}` for forward-compat. (ADR-002)
- **Gradle 9.0, Kotlin DSL.** `build.gradle.kts`, `settings.gradle.kts`, version catalog at `gradle/libs.versions.toml`. Wrapper pinned to **Gradle 9.0.0** — the first release that supports **JDK 25** for both daemon and toolchain (8.x maxed at JDK 24). The daemon still runs on JDK 17 via `JAVA_HOME` for stability (CI workflows order `setup-java`'s `java-version` so the matrix JDK is first and 17 is last); toolchain forks compile/test JDKs as needed via `-PtestJdk=N`. Standard plugins: `java-library`, `maven-publish`, Vanniktech Maven Publish (or Gradle Nexus Publish), Spotless, JaCoCo. Integration tests live in a separate `integrationTest` source set, env-var-gated. (ADR-003)
- **`java.net.http.HttpClient` exclusively.** No third-party HTTP client (OkHttp, Apache) as a runtime dep — ever. HTTP/2 on (default). One shared `HttpClient` per `MarketDataClient`. Timeouts: 99s request, 2s connect. (ADR-004)
- **Jackson (`jackson-databind`) for JSON.** Records-based response models (Jackson record support, 2.12+). The API's parallel-arrays wire format (e.g. `{"s":"ok","symbol":["AAPL","MSFT"],"price":[150.0,400.0]}`) is decoded via custom `JsonDeserializer` classes, *not* default reflection. Jackson is **not shaded** in v1; shading is held in reserve. (ADR-005)
- **Sync + async parity per endpoint.** Every public endpoint exposes both `quote(...)` and `quoteAsync(...)`; async returns `CompletableFuture<T>`. **Internal logic is async-first.** Sync methods are thin wrappers that call `.join()` and unwrap `CompletionException` to surface the underlying cause directly. Both surfaces share validation, retry, rate-limit, and concurrency-pool logic — no parallel implementations. Tests must cover both variants for every endpoint. (ADR-006)
- **Single-package internals.** Every infra and resource-façade class lives in `com.marketdata.sdk` (the root). The "internal" boundary is enforced by Java's package-private visibility — types not meant for consumers (`Configuration`, `EnvVars`, `Tokens`, `Version`, and the future `HttpTransport`, `RequestSpec`, `AsyncSemaphore`, etc.) drop the `public` modifier so the consumer's compiler simply cannot reference them. Resource façades (`MarketsResource`, etc.) stay `public final class` but with package-private constructors. Response DTOs and exceptions stay in their public subpackages (`com.marketdata.sdk.markets`, `com.marketdata.sdk.exception`); response records do not carry `@JsonDeserialize` annotations — wire-format deserializers register programmatically via a package-private Jackson `SimpleModule` on `HttpTransport`'s `ObjectMapper`. (ADR-007)

## Kotlin-interop rules for the public API

Even though sources are Java-only, Kotlin consumers are a first-class audience. Anything you put on the public API must satisfy these (see `docs/java-sdk-requirements.md` §2 for the full list):

- `@NullMarked` at the package level (in `package-info.java`) so non-null is the default; mark nullable items explicitly with JSpecify `@Nullable`. Without these, Kotlin sees Java values as platform types (`String!`).
- **No Kotlin reserved words** as public method or parameter names: `object`, `is`, `in`, `fun`, `when`, `as`, `val`, `var`, `typealias`, `interface`, `package`, `typeof`, `out`, `super`. They force Kotlin callers into backticks.
- **Getters are property reads in Kotlin.** No expensive work, I/O, or observable side effects in `getFoo()` / `isFoo()`. Use consistent `getFoo` / `isFoo` naming.
- **Callbacks must be SAM** (single abstract method, no `default` second method). Prefer `java.util.function.*` types where applicable.
- **Wildcards on generic public APIs.** Producer params: `? extends T`. Consumer params: `? super T`. Missing wildcards translate to invariant Kotlin types.
- **Return standard JVM collections** (`List`, `Map`, `Set`); never arrays for variable-length results; return empty collections rather than `null`.
- **No `Optional<T>` in fields or parameters.** `Optional<T>` is only acceptable as a return type on Java-facing methods; Kotlin callers prefer nullable returns.
- **No `kotlinx-coroutines` dependency.** Kotlin consumers bridge `CompletableFuture` via `kotlinx-coroutines-jdk8`'s `await()` themselves.
- README and per-method docs must include a Kotlin example alongside the Java example for the quick-start path.

## Why the JDK-17 features matter (don't second-guess them)

ADR-002 picked JDK 17 specifically to enable two features that shape the public API:

- **Records** for response models — collapses ~30 lines of POJO boilerplate per model. Use records by default for response shapes.
- **Sealed exception hierarchy** rooted at `MarketDataException`, permitting the closed set of error subtypes (`AuthenticationError`, `RateLimitError`, etc.). The point is compiler-enforced exhaustive `switch` at consumer call sites — adding a new subtype in a future major version must break consumer switches at compile time.

If you find yourself reaching for Lombok, AutoValue, or an abstract-base exception class, stop — that's reverting an explicit ADR-002 decision.

## Cross-language SDK requirements

The Java SDK must also satisfy the canonical, cross-language [SDK Requirements](https://www.marketdata.app/docs/sdk/sdk-requirements/) (referenced from inside the ADRs as `../sdk-requirements.md`). The current scaffold applies the **foundational** rules from that doc; per-endpoint and per-request rules land alongside the request layer. Specifically:

**Already wired in:**
- §1.1 client object — `MarketDataClient` with two public constructors: a no-arg one for production (everything from the cascade) and a 4-arg `(apiKey, baseUrl, apiVersion, validateOnStartup)` for tests and short-lived runtimes. All fields `final` (immutable). Default base URL `https://api.marketdata.app`, default API version `v1`, single shared `HttpClient`, `User-Agent: marketdata-sdk-java/{version}` (version auto-detected from JAR manifest), `close()` for resource release, `getRateLimits()` accessor.
- §4 configuration cascade — `Configuration.resolve(...)` does explicit → `MARKETDATA_*` env var → `.env` in CWD → default. Env var names live in `EnvVars` (package-private, in the SDK root package). `baseUrl` and `apiVersion` are normalized (trailing/leading slashes stripped) and validated (scheme http/https, host present, no query/fragment/user-info on `baseUrl`; `[A-Za-z0-9._-]+` on `apiVersion`) at resolution time, so misconfigured cascade inputs fail at construction with a clear message instead of producing malformed URLs later.
- §5 demo mode + `validateOnStartup` parameter on the 4-arg constructor (defaults to `true` via the no-arg constructor); token redaction via `Tokens.redact` (matches the spec example `***…***YKT0`). `runStartupValidation` fires a single `GET /user/` (unversioned, like `/status/` and `/headers/`) via `utilities.validateAuth()` with `RetryPolicy.noRetry()`, skipping in demo mode; the no-retry policy keeps construction snappy on a slow/down API.
- §6 sealed `MarketDataException` hierarchy with the 7 canonical subtypes and full support context (`requestId`, `requestUrl`, `statusCode`, `timestamp`, `exceptionType`) + `getSupportInfo()`.
- §7 logging — `MarketDataLogging.configure(...)` installs `CanonicalLogFormatter` (the spec's `{timestamp} - {logger_name} - {level} - {message}` shape) on `com.marketdata.sdk` and applies the level from `MARKETDATA_LOGGING_LEVEL`. Detects consumer-pre-configured loggers (handler attached or level set) and backs off entirely so embedding the SDK doesn't clobber existing logging setups.
- §8 rate-limit tracking — `RateLimitHeaders.parse` reads the four `x-api-ratelimit-*` headers per response (all-or-nothing: a partial header set returns null rather than emitting a snapshot with phantom zeros); `HttpTransport.latestRateLimits` keeps the latest snapshot, exposed via `client.getRateLimits()`. §10.3 preflight (`HttpTransport.checkRateLimitPreflight`) fails fast on `remaining=0`, but only while `now < reset` — once the reset window elapses the request goes through so the next response refreshes the snapshot.
- §9 retry/backoff — `RetryPolicy` (4 total attempts = 1 initial + 3 retries, exponential 1s→30s per §9.3) wired into `HttpTransport.executeAsync` via a per-attempt loop using `CompletableFuture.delayedExecutor` (no scheduled threads). Network errors and HTTP 501–599 retry; 500 and 4xx do not. §9.4 `Retry-After`: parsed from response headers via `RetryAfterHeader.parse` (supports both delta-seconds and HTTP-date), attached to `ServerError`, and honored by `RetryPolicy.backoffDelay` as an override of the calculated exponential delay. §9.5 `/status/` cache: `StatusCache` (stale-while-revalidate, 270s refresh / 300s expiry) gates retries on services reported offline; `HttpTransport.cacheAllowsRetry` has a self-referential bypass for `/status/` itself so the cache can never block its own refresh.
- §10 timeouts: `REQUEST_TIMEOUT = 99s` and `CONNECT_TIMEOUT = 2s` exposed as constants on `MarketDataClient`. Connect timeout is wired into the `HttpClient`; the per-request 99 s timeout is applied via `HttpRequest.Builder#timeout` in `HttpTransport.buildRequest`.
- §12 concurrency: 50-permit `AsyncSemaphore` on `HttpTransport` with acquire/release wired around every dispatch. The custom semaphore replaces `java.util.concurrent.Semaphore` so `executeAsync` never parks the caller's thread on a full pool (ADR-007).
- §13.5 response object — `Response<T>` wrapper exposes typed `data()`, `rawBody()` (defensive copy), `statusCode()`, `requestUrl()`, `requestId()`, format predicates (`isJson()`/`isCsv()`/`isHtml()`), `isNoData()`, and `saveToFile(Path)`. Every resource endpoint returns `Response<T>` so consumers get a uniform surface regardless of format.
- §15 packaging: SemVer, MIT `LICENSE`, `CHANGELOG.md` in Keep a Changelog format, version auto-detected via JAR manifest (`Implementation-Version`).
- §16 security: tokens never logged verbatim (use `Tokens.redact`); TLS validated by default (`HttpClient` does not expose a skip-verify option); query strings stripped from log output (logged as `/path?…`) so request params never persist to logs — exception context retains the full URI for diagnostic use; `EnvVars.systemLookup()` restricts reads to the declared `MARKETDATA_*` keys.
- ADR-002 CI: split into four workflows.
  - `.github/workflows/pull-request.yml` — runs on PR `opened`/`synchronize`/`reopened` (no pre-PR push trigger by design). JDK 17 only. Runs `./gradlew build` (unit tests + Spotless + JaCoCo) and uploads coverage to Codecov. **Does not** run integration tests — those are handled by the on-demand workflow below.
  - `.github/workflows/main.yml` — runs only on `push` to `main`. Two jobs: `verify` does the full forward-compat matrix `{17, 21, 25}` for unit tests via `-PtestJdk=N`; `integration-tests` does a parallel matrix `{17, 21, 25}` against the live API. Both are mandatory for the merge to be considered successful. The JDK 17 matrix entry of `verify` also uploads coverage to Codecov as the new baseline that PRs compare against. `integration-tests` fails the build if `MARKETDATA_TOKEN` secret is absent (it is required on main).
  - `.github/workflows/pr-matrix-on-demand.yml` — manually triggered on a PR by commenting `/run-all-jdks`, `/jdk-matrix`, or `/test-all`. Runs the **unit-test** matrix on JDK 21 and 25 (17 already ran via `pull-request.yml`). Gated to write/maintain/admin commenters. Reacts 👀 to the trigger comment and posts a result summary.
  - `.github/workflows/pr-integration-on-demand.yml` — manually triggered on a PR by commenting `/integrationtest` (JDK 17 only) or `/integrationtestfull` (matrix `{17, 21, 25}`) on the **first line** of the comment body. Runs the **integration-test** suite against the live API. Same write+ permission gate as the matrix-on-demand workflow. The first-line + exact-match constraint prevents accidental triggers from quoted replies (`> /integrationtest`) or prose that mentions the command. Aggregates the matrix outcome into a single required check named **"Integration tests pass"** so branch protection can require it uniformly regardless of which command was used. Branch-protection rules on `main` should list this check as required for merge.
  - All four `issue_comment`-driven workflows execute from the default branch's copy of their YAML, not the PR's. Feature-branch edits to these workflows take effect only after merge to main.
  - `-PtestJdk=N` is wired to **all** `Test` tasks (`test` and `integrationTest`) via `tasks.withType<Test>().configureEach { javaLauncher.set(...) }` in `build.gradle.kts`, so the matrix flag works uniformly across unit and integration tests.
  - Coverage ratchet lives in `codecov.yml`: project status with `target: auto, threshold: 5%` (cannot drop >5 pp vs base branch) plus a patch-coverage requirement of 70 % on new code. Requires a `CODECOV_TOKEN` repo secret — without it the upload step fails because workflows pass `fail_ci_if_error: true`.

**Deliberately deferred (require the per-resource layer to land first):**
- §1.2 resource groupings — only `client.utilities()` is wired today; `client.stocks`, `client.options`, `client.funds`, `client.markets` still to come.
- §2 endpoint method coverage; §3 universal parameters; §11 wire-format decoding for resources beyond utilities. The plumbing (`ParallelArrays.zip` helper for the parallel-arrays shape, `JsonResponseParser`, `Response<T>` wrapper) is in place — each new endpoint just declares its fields and row builder.
- §8 request-scoped rate-limit attachment — today the snapshot is client-level (via `client.getRateLimits()`); attaching a per-response snapshot to `Response<T>` is a small follow-up when a consumer needs it.
- §13 100% coverage threshold via JaCoCo `violationRules`; deferred until the resource layer lands so the threshold meaningfully ratchets functional code, not just scaffolding.

When picking up new work, check this list before reaching for the SDK requirements doc — most foundational rules are already encoded in code; missing pieces are deferred deliberately, not by accident.

## Acceptance checklist

`docs/java-sdk-requirements.md` ends with an "Acceptance Checklist" mapping each Java-specific requirements section to verifiable items. Treat it as the definition of done for v1: when implementing, work toward making each box checkable, and use it as a self-review pass before declaring a section complete.
