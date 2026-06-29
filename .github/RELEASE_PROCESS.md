# Java SDK Release Process

This document defines the release process for `MarketDataApp/sdk-java`, including the pre-release workflow we use before cutting a tag.

## 1. Scope

Use this process for:
- patch releases (`vX.Y.Z`)
- minor releases (`vX.Y.0`)
- major releases (`vX.0.0`)

## 2. Release Inputs

Before starting, confirm:
- target release version `X.Y.Z`
- release tag format: `vX.Y.Z`
- release title format: `Version X.Y.Z`
- release owner
- included PRs/issues
- intended release date/time

## 3. Pre-Release Workflow (Current)

Our pre-release gate artifacts live in `release-readiness/` and are reviewed as a package before tag cut.

Required gate docs:
- `release-readiness/01-api-contract.md`
- `release-readiness/02-quality-and-tests.md`
- `release-readiness/03-compatibility.md`
- `release-readiness/04-security.md`
- `release-readiness/05-docs-dx.md`
- `release-readiness/06-release-rollback.md`
- `release-readiness/final-go-no-go.md`

Gate execution checklist:
1. API contract gate:
   - Confirm intended API/signature changes and migration impact.
   - Record pass/fail in `01-api-contract.md`.
2. Quality/test gate:
   - `./gradlew build` (runs unit tests + Spotless formatting check + JaCoCo coverage).
   - Forward-compat matrix: `./gradlew test -PtestJdk=<17|21|25>` for each target JDK.
   - Integration tests against the live API: `MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew integrationTest -PtestJdk=<N>` (a valid `MARKETDATA_TOKEN` and network-enabled context required).
   - Record evidence paths and pass/fail in `02-quality-and-tests.md`.
3. Compatibility gate:
   - Confirm the `Main` workflow (`.github/workflows/main.yml`) is green for the release commit — it runs the full `{17, 21, 25}` unit + integration matrix on every push to `main`.
   - Record results in `03-compatibility.md`.
4. Security gate:
   - Review transitive dependencies (e.g. `./gradlew dependencies`) and confirm no unexpected runtime additions slipped in.
   - Confirm token handling stays header-based (`Authorization: Bearer`, redacted in logs via the SDK's `Tokens` utility) and that TLS verification is never disabled.
   - Record results in `04-security.md`.
5. Docs/DX gate:
   - Verify `README.md`, `CHANGELOG.md`, `build.gradle.kts` (default version), and `docs/installation.md` version/support messaging align.
   - Run the executable examples in `examples/consumer-test` (the `examples/common` and `examples/resources` sample apps, and the Kotlin `Quickstart.kt`).
   - Record results in `05-docs-dx.md`.
6. Release/rollback gate:
   - Confirm no open blockers.
   - Update rollback path for a patch follow-up release.
   - Record in `06-release-rollback.md`.
7. Final decision:
   - Set `GO` or `NO-GO` in `final-go-no-go.md`.
   - No tag is cut unless status is `GO` and P0 blockers are empty.

## 4. Release Preparation

1. Ensure `main` is current and CI is green.

2. **Update version numbers** in the following files:

   | File | Location | Example |
   |------|----------|---------|
   | `README.md` | Title header | `# Market Data Java & Kotlin SDK v1.0` |
   | `build.gradle.kts` | `version = ... ?: "X.Y.Z-SNAPSHOT"` default | `1.0.0-SNAPSHOT` |
   | `docs/installation.md` | Gradle/Maven coordinates | `app.marketdata:marketdata-sdk-java:X.Y.Z` |

   > **Note**: The published artifact version is injected at publish time via `-PsdkVersion=X.Y.Z`. The version committed in `build.gradle.kts` intentionally stays a `-SNAPSHOT` default — it is overridden by the release and publish workflows, not by hand-editing for each release.

3. **Update CHANGELOG.md** with final release notes (Keep a Changelog bracket format):
   - Add a new `## [X.Y.Z] - YYYY-MM-DD` section (move items out of `## [Unreleased]`; do **not** use a `## vX.Y.Z` heading — the release workflow matches `## [X.Y.Z]`).
   - Update the compare-link references at the bottom of the file (e.g. set `[Unreleased]` to `compare/vX.Y.Z...HEAD` and add a `[X.Y.Z]` link).
   - Verify all breaking changes have migration guides.
   - Ensure highlights, breaking changes, and migration notes are complete.

4. Commit and push all changes to `main`.

5. Confirm target tag does not already exist.

> **Important**: The release workflow extracts release notes directly from CHANGELOG.md.
> The `## [X.Y.Z]` section must be present and complete before triggering the release.

## 5. Publish Release

One workflow drives the whole release. **Tag and Release** (`tag-and-release.yml`) runs the test gate, cuts the tag and GitHub Release, and then — unless you opt out — chains directly into the Maven Central publish. Each stage gates the next, so a red test or a non-green `main.yml` stops the release before anything is pushed.

Go to Actions → "Tag and Release", click "Run workflow", and fill in:
   - **version**: `X.Y.Z` (without `v` prefix)
   - **ref**: `main` (or specific commit SHA)
   - **prerelease**: `false` (unless it's a prerelease)
   - **publish_to_central**: `true` (default — chain into Maven Central; set `false` to stop after the GitHub Release)
   - **confirm**: `RELEASE` (exactly, to confirm)

The run proceeds through three gated jobs:

1. **`gate`** — JDK `{17, 21, 25}` matrix (`./gradlew build -PtestJdk=<N> -PsdkVersion=X.Y.Z`). Must pass before anything else.
2. **`release`** — verifies the tag `vX.Y.Z` is new, extracts release notes from CHANGELOG.md (the `## [X.Y.Z]` section), creates the tag `vX.Y.Z` and the GitHub Release "Version X.Y.Z".
3. **`publish-central`** (only when `publish_to_central` is `true`) — calls `publish.yml`, which **independently re-checks that `main.yml` is green for the commit**, rebuilds + tests, then pushes the artifact (`app.marketdata:marketdata-sdk-java`) to Maven Central.

> **Stopping before Central.** Set **publish_to_central** to `false` to cut only the tag + GitHub Release. You can then publish later by running the **Publish to Maven Central** workflow (`publish.yml`) directly: **version** = `X.Y.Z`, **release** = `true`. Running that workflow with **release** = `false` uploads to the Sonatype Portal and stops at `VALIDATED` for manual review — useful for inspecting the staged artifact before promoting it.

## 6. Post-Release Checks

1. Verify the GitHub Release was created with correct notes from CHANGELOG.
2. Confirm the artifact is visible on Maven Central (https://central.sonatype.com/artifact/app.marketdata/marketdata-sdk-java) — note that Central indexing can lag a few minutes after publish.
3. Smoke-test resolution in a clean project by adding the dependency and resolving it:

```kotlin
// build.gradle.kts
dependencies {
    implementation("app.marketdata:marketdata-sdk-java:X.Y.Z")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>app.marketdata</groupId>
  <artifactId>marketdata-sdk-java</artifactId>
  <version>X.Y.Z</version>
</dependency>
```

## 7. Rollback and Hotfix

If release issues are discovered:
1. Stop promotion messaging.
2. Publish corrective note in release/changelog.
3. Ship a patch release (`vX.Y.(Z+1)`) from `main` with targeted fix.
4. Document root cause and remediation in next changelog entry.
</content>
</invoke>
