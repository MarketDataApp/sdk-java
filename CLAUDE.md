# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repo currently contains **documentation only** — no Java sources, no build scripts. Branch `00_base_setup` is the pre-implementation phase: all foundational technical decisions are being captured as ADRs *before* code lands. There is therefore nothing to build, lint, or test yet. When implementation starts, the build will be Gradle (Kotlin DSL) per ADR-003 — see "Locked-in tech stack" below.

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
- **Gradle, Kotlin DSL.** `build.gradle.kts`, `settings.gradle.kts`, version catalog at `gradle/libs.versions.toml`. Standard plugins: `java-library`, `maven-publish`, Vanniktech Maven Publish (or Gradle Nexus Publish), Spotless, JaCoCo. Integration tests live in a separate `integrationTest` source set, env-var-gated. (ADR-003)
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
- §4 configuration cascade — `Configuration.resolve(...)` does explicit → `MARKETDATA_*` env var → `.env` in CWD → default. Env var names live in `EnvVars` (package-private, in the SDK root package). The 4-arg constructor's parameters feed step 1; the no-arg constructor skips it and starts at step 2.
- §5 demo mode + `validateOnStartup` parameter on the 4-arg constructor (defaults to `true` via the no-arg constructor); token redaction via `Tokens.redact` (matches the spec example `***…***YKT0`).
- §6 sealed `MarketDataException` hierarchy with the 7 canonical subtypes and full support context (`requestId`, `requestUrl`, `statusCode`, `timestamp`, `exceptionType`) + `getSupportInfo()`.
- §10 timeouts: `REQUEST_TIMEOUT = 99s` and `CONNECT_TIMEOUT = 2s` exposed as constants on `MarketDataClient`. Connect timeout is wired into the `HttpClient`; the per-request 99 s timeout is a constant ready to be applied to `HttpRequest.Builder#timeout` when the request layer lands.
- §12 concurrency: `Semaphore(50)` field on `MarketDataClient` (wiring of acquire/release lands with the request layer).
- §15 packaging: SemVer, MIT `LICENSE`, `CHANGELOG.md` in Keep a Changelog format, version auto-detected via JAR manifest (`Implementation-Version`).
- §16 security: tokens never logged verbatim (use `Tokens.redact`); TLS validated by default (`HttpClient` does not expose a skip-verify option).
- ADR-002 CI: split into three workflows.
  - `.github/workflows/pull-request.yml` — runs on PR `opened`/`synchronize`/`reopened` (no pre-PR push trigger by design). JDK 17 only. Runs `./gradlew build` and uploads `build/reports/jacoco/test/jacocoTestReport.xml` to Codecov.
  - `.github/workflows/main.yml` — runs only on `push` to `main`. Full forward-compat matrix `{17, 21, 25}` via `-PtestJdk=N` (wired into `tasks.test.javaLauncher` in `build.gradle.kts`). The JDK 17 matrix entry also uploads coverage to Codecov, establishing the base coverage that PRs compare against.
  - `.github/workflows/pr-matrix-on-demand.yml` — manually triggered by commenting one of `/run-all-jdks`, `/jdk-matrix`, or `/test-all` on an open PR. Runs JDK 21 and 25 (17 already ran via `pull-request.yml`). Gated to commenters with write/maintain/admin permission. Reacts 👀 to the trigger comment and posts a result summary comment when the matrix finishes. Note: `issue_comment` workflows always execute from the default branch's copy of the file — feature-branch edits to this workflow have no effect until merged to main.
  - Coverage ratchet lives in `codecov.yml`: project status with `target: auto, threshold: 5%` (cannot drop >5 pp vs base branch) plus a patch-coverage requirement of 70 % on new code. Requires a `CODECOV_TOKEN` repo secret — without it the upload step fails because workflows pass `fail_ci_if_error: true`.

**Deliberately deferred (require the request/endpoint layer to land first):**
- §1.2 resource groupings (`client.stocks`, `client.options`, `client.funds`, `client.markets`, `client.utilities`).
- §2 endpoint method coverage; §3 universal parameters; §11 wire-format decoding.
- §5 actual `/user/` startup validation call (the `validateOnStartup` flag is the seam; the call itself comes with the request layer).
- §7 honoring `MARKETDATA_LOGGING_LEVEL` and the spec's exact `{timestamp} - {logger_name} - {level} - {message}` format. Currently the SDK uses `java.util.logging` with default formatting; consumers can attach their own handler.
- §8 rate-limit header parsing, pre-flight check, request-scoped attachment.
- §9 retry/backoff policy and `/status/` cache workflow.
- §12 acquire/release of the concurrency semaphore around dispatched requests.
- §13 100% coverage threshold via JaCoCo `violationRules`; deferred until there is functional code worth the threshold.
- §13 integration-test CI job: stubbed at the bottom of `ci.yml` with a comment. Gating on a `MARKETDATA_TOKEN` GitHub Actions secret; will be wired up once the secret exists.

When picking up new work, check this list before reaching for the SDK requirements doc — most foundational rules are already encoded in code; missing pieces are deferred deliberately, not by accident.

## Acceptance checklist

`docs/java-sdk-requirements.md` ends with an "Acceptance Checklist" mapping each Java-specific requirements section to verifiable items. Treat it as the definition of done for v1: when implementing, work toward making each box checkable, and use it as a self-review pass before declaring a section complete.
