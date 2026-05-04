# Market Data Java SDK

Java SDK for the [Market Data API](https://www.marketdata.app/). **Pre-release
scaffold** — endpoints are not yet implemented; this iteration sets up the
build, package layout, configuration cascade, exception taxonomy, and
Kotlin-interop foundations from the [ADRs](docs/adr/) and the canonical
[SDK Requirements](https://www.marketdata.app/docs/sdk/sdk-requirements/).

## Requirements

- **JDK 17 or newer** (ADR-002). The published artifact is compiled with
  `javac --release 17`. Tests run on JDK 17, 21, and 25.
- **Jackson 2.18+** on the runtime classpath (ADR-005). Pulled transitively;
  consumers may align to a newer 2.x.

## Install (planned)

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.marketdata:marketdata-sdk-java:0.1.0")
}
```

Coordinates are placeholders until the first publication to Maven Central.

## Quick start

The SDK reads `MARKETDATA_TOKEN` from the environment by default, so the
common path is two lines (per SDK requirements §"Easy Default Requests"):

### Java

```java
try (var client = MarketDataClient.builder().build()) {
    // endpoint methods land in subsequent iterations
}
```

### Kotlin

```kotlin
MarketDataClient.builder().build().use { client ->
    // endpoint methods land in subsequent iterations
}
```

## Configuration

Values are resolved through this cascade (highest priority first), per
SDK requirements §4:

1. Explicit builder methods — `apiKey(...)`, `baseUrl(...)`, `apiVersion(...)`
2. Environment variables (table below)
3. `.env` file in the current working directory
4. Built-in defaults

### Environment variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `MARKETDATA_TOKEN` | API authentication token | (none — demo mode) |
| `MARKETDATA_BASE_URL` | API base URL | `https://api.marketdata.app` |
| `MARKETDATA_API_VERSION` | API version | `v1` |
| `MARKETDATA_LOGGING_LEVEL` | SDK logging level | `INFO` |
| `MARKETDATA_OUTPUT_FORMAT` | Default output format | (language default) |
| `MARKETDATA_DATE_FORMAT` | Default date format | `timestamp` |
| `MARKETDATA_COLUMNS` | Columns to include | (all) |
| `MARKETDATA_ADD_HEADERS` | Include headers in CSV | `true` |
| `MARKETDATA_USE_HUMAN_READABLE` | Human-readable field names | `false` |
| `MARKETDATA_MODE` | Data mode (live/cached/delayed) | `live` |

Endpoint-shape variables (`OUTPUT_FORMAT`, `DATE_FORMAT`, `COLUMNS`,
`ADD_HEADERS`, `USE_HUMAN_READABLE`, `MODE`) are reserved here and will be
honored when the request layer lands.

### Demo mode

Building a client without a token (no explicit `apiKey()`, no env var, no
`.env` entry) puts the client in **demo mode**: the `Authorization` header
is omitted from outbound requests and the SDK logs a warning at INFO
level. Authenticated endpoints will fail. Use this for read-only, public
endpoints only.

## Error handling

All SDK errors extend the sealed [`MarketDataException`](src/main/java/com/marketdata/sdk/exception/MarketDataException.java)
hierarchy and carry support context (`requestId`, `requestUrl`,
`statusCode`, `timestamp`) plus a `getSupportInfo()` helper for support
tickets:

```java
try {
    // call endpoint method (forthcoming)
} catch (RateLimitError e) {
    System.err.println(e.getSupportInfo());
}
```

The seven permitted subtypes — `AuthenticationError`, `BadRequestError`,
`NotFoundError`, `RateLimitError`, `ServerError`, `NetworkError`,
`ParseError` — match SDK requirements §6.1. The hierarchy is sealed so
`switch` over the subtypes is compile-time exhaustive (ADR-002).

## Build

The repo uses **Gradle (Kotlin DSL)** with a version catalog at
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

The Gradle wrapper jar is **not** committed. Bootstrap once with a
locally installed Gradle (≥ 8.10):

```bash
gradle wrapper --gradle-version 8.12
```

After that, use the wrapper for everything:

```bash
./gradlew build               # compile + unit tests + spotless + jacoco
./gradlew test                # unit tests only
./gradlew spotlessApply       # auto-format
./gradlew jacocoTestReport    # coverage report → build/reports/jacoco/

# Integration tests hit the live API — gated by env var (ADR-003 §13).
MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest
```

## Package layout

```
com.marketdata.sdk             # MarketDataClient, RateLimits (public surface)
com.marketdata.sdk.exception   # Sealed MarketDataException hierarchy + ErrorContext
com.marketdata.sdk.internal    # Tokens, EnvVars, Configuration, Version (do not depend on)
```

Every public package is `@NullMarked` (JSpecify): non-null is the default;
nullable items are tagged explicitly. This is what makes Kotlin's null
safety work against this Java API (ADR-001 §2.1).

## Architectural decisions

All foundational decisions are captured as ADRs and are **Accepted**:

| ADR | Decision |
|-----|----------|
| [001](docs/adr/ADR-001-java-only-vs-multi-language-sdk.md) | Java only; Kotlin consumers via interop, not a Kotlin artifact |
| [002](docs/adr/ADR-002-minimum-jdk-version.md) | Minimum JDK 17; CI matrix `{17, 21, 25}` |
| [003](docs/adr/ADR-003-build-tool.md) | Gradle (Kotlin DSL) + version catalog |
| [004](docs/adr/ADR-004-http-client.md) | `java.net.http.HttpClient` exclusively |
| [005](docs/adr/ADR-005-json-library.md) | Jackson (`jackson-databind`) |
| [006](docs/adr/ADR-006-async-api-surface.md) | Sync + async parity, async-first internally |

Java-specific requirements derived from the ADRs live in
[`docs/java-sdk-requirements.md`](docs/java-sdk-requirements.md). The
canonical, cross-language requirements are at
[marketdata.app/docs/sdk/sdk-requirements](https://www.marketdata.app/docs/sdk/sdk-requirements/).

## License

[MIT](LICENSE).
