# ADR-003: Build Tool

## Status

Proposed — under discussion.

## Context

The Java SDK needs a build tool to compile, test, package, and publish
the artifact to Maven Central (per the requirements doc §15). The choice
affects contributors and CI but **does not affect consumers** — the SDK
is consumed via its published artifact regardless of how it was built.
A consumer using Maven can integrate a Gradle-built SDK and vice versa.

Three properties matter for this SDK specifically:

1. **Multiple source sets**, because unit tests are mocked but
   integration tests hit the live API and must be in a separate, gated
   source set (requirements §13).
2. **Maven Central publishing**, including signing, staging, and
   release lifecycle.
3. **CI matrix support**, because we'll likely build/test on multiple
   JDK versions even after ADR-002 picks a minimum target.

The existing Python SDK uses `uv`/`pyproject.toml`; that has no bearing
on this decision since it's a different language ecosystem.

## Options Considered

### Option A — Gradle (Kotlin DSL)

Build script in `build.gradle.kts`, configured via Kotlin.

**Pros**

- Type-safe build script. The Kotlin DSL gives compile-time errors,
  IDE auto-complete, and refactoring support inside the build file.
- Native, first-class support for multiple source sets. Standing up a
  separate `integrationTest` source set is a few lines of config.
- Faster incremental builds and a configuration cache that materially
  speeds up local iteration.
- Adopted by most modern Java/Kotlin libraries published in the last
  five years (Spring Framework, OkHttp, Kotlin itself, AndroidX).
- Maven Central publishing is well-supported via the official
  `maven-publish` plugin combined with the Gradle Nexus Publish Plugin
  or the Vanniktech Maven Publish Plugin.

**Cons**

- Smaller pool of Java developers who already know it. Maven is still
  more universally familiar in enterprise Java.
- Kotlin DSL has historically had occasional friction on Gradle major
  upgrades; the script is code, and code can break.
- More moving parts than Maven (settings file, properties file, plugin
  declarations, version catalog).

### Option B — Gradle (Groovy DSL)

Build script in `build.gradle`, configured via Groovy.

**Pros**

- The original Gradle DSL; the largest body of Stack Overflow answers,
  blog posts, and existing examples uses it.
- Slightly less ceremony for very simple scripts.
- Same multi-source-set support, same incremental builds, same
  publishing story as Option A.

**Cons**

- No type safety in the build script. Errors are runtime errors. IDEs
  cannot reliably auto-complete or refactor.
- Groovy itself is in maintenance mode in most ecosystems. Writing
  Groovy in 2026 to configure a Java project feels backward.
- Gradle's official direction is Kotlin DSL — new docs and examples
  increasingly default to it.

### Option C — Maven

Build script in `pom.xml`, configured via XML.

**Pros**

- Most universally familiar Java build tool. Lowest cognitive load for
  a new contributor walking in cold.
- Convention-over-configuration: the `pom.xml` for an SDK of this
  shape is short and predictable.
- Mature publishing flow to Maven Central via `maven-deploy-plugin` +
  `maven-gpg-plugin` + `nexus-staging-maven-plugin`.
- Reproducible by construction. There is one official way to do most
  things, which suits a small team with no Maven specialist.
- Used by AWS SDK for Java v2, Google Cloud Java client libraries, and
  most other large enterprise SDKs.

**Cons**

- XML. Verbose for non-trivial configuration; no programmability.
- Multi-source-set isolation (separating integration tests from unit
  tests) requires the `maven-failsafe-plugin` and a structured
  layout — workable but more verbose than Gradle's source set DSL.
- Slower builds; no incremental compilation, no configuration cache.
- Plugin authoring and customization are noticeably harder than
  Gradle — though this SDK is unlikely to need custom plugins.

### Option D — Bazel

Build script in `BUILD.bazel` files, with `WORKSPACE`/`MODULE.bazel`.

**Pros**

- Hermetic, deterministic builds. Excellent for very large monorepos.
- Strong remote caching and remote execution support.

**Cons**

- Massive operational overhead for a single-artifact SDK.
- Java rules ecosystem is less mature than Gradle/Maven for publishing
  to Maven Central.
- Steep learning curve; small contributor pool.
- Listed for completeness only; not a serious option for this scope.

## Claude's Recommendation

**Option A (Gradle Kotlin DSL).**

The deciding factors:

1. The integration-test gating requirement (§13) maps cleanly to a
   Gradle source set with a few lines of config; the Maven equivalent
   is a multi-plugin configuration that's harder to read.
2. Type-safe build scripts age better than dynamic ones. We're going
   to add publishing config, signing config, JaCoCo, Spotless, and a
   CI matrix — all of those are easier to keep correct in a typed DSL.
3. Modern Java library tooling is increasingly Gradle-default. We'll
   pull plugins (Spotless, Vanniktech publish, etc.) where the Gradle
   support is the first-class path.

The strongest counter-recommendation is **Option C (Maven)** on the
basis that "lowest contributor friction" beats "slightly nicer build
script" for a small team. That's defensible. The deciding question is
whether the team has more Maven or more Gradle muscle memory.

**Option B (Gradle Groovy)** has no advantages over Option A in 2026
and should be ruled out unless there's an existing Groovy build to
copy from.

## Decision

*To be filled in by the team.*

## Consequences

*To be filled in once a decision is made.*

- **A (Gradle Kotlin DSL):** `build.gradle.kts`, `settings.gradle.kts`,
  `gradle/libs.versions.toml` (version catalog). Plugin choices: 
  `java-library`, `maven-publish`, Vanniktech or Nexus Publish for
  Central, Spotless for formatting, JaCoCo for coverage.
- **B (Gradle Groovy):** as A but `.gradle` files, sacrificing type
  safety.
- **C (Maven):** `pom.xml`, multi-module structure if needed,
  `maven-failsafe-plugin` for integration tests, `nexus-staging-maven-plugin`
  for Central, `jacoco-maven-plugin`, `spotless-maven-plugin`.
- **D (Bazel):** not recommended.

## References

- [Gradle Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Maven Central publishing guide](https://central.sonatype.org/publish/publish-guide/)
- [Vanniktech Maven Publish Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
- [Gradle Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin)
- AWS SDK for Java v2 (Maven), Spring Framework 7 (Gradle Kotlin DSL),
  OkHttp (Gradle Kotlin DSL) — reference points
