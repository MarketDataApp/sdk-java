import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.vanniktech.publish)
}

// Maven groupId = the verified Central Portal namespace (domain marketdata.app,
// reversed). Independent of the Java package, which stays com.marketdata.sdk.
group = "app.marketdata"

// Version is overridable from the command line so a manual Central Portal
// validation run can use a real release version (e.g. `-PsdkVersion=0.1.0`)
// without committing it. Default stays on the in-development SNAPSHOT.
version = (findProperty("sdkVersion") as String?) ?: "0.1.0-SNAPSHOT"

// ADR-002: minimum JDK 17, build with --release 17, single bytecode level.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    // Sources/Javadoc jars are produced by the Vanniktech publish plugin
    // (see mavenPublishing block below). Duplicating them via
    // withJavadocJar()/withSourcesJar() here causes "multiple artifacts
    // with classifier 'javadoc'" failures at publish time.
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

// SDK requirements §15: version must be auto-detected from package
// metadata. Internal Version.current() reads this attribute at runtime.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "marketdata-sdk-java",
            "Implementation-Version" to project.version,
        )
    }
}

// ADR-003: integration tests live in a separate, env-var-gated source set.
val integrationTest by sourceSets.creating {
    // Wire the main and unit-test outputs into the integration test classpath
    // so ITs can use both production code and test helpers (junit, assertj).
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against the live Market Data API."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    onlyIf {
        System.getenv("MARKETDATA_RUN_INTEGRATION_TESTS") == "true"
    }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTestTask) }

dependencies {
    // ADR-001 §2.1: JSpecify nullability annotations are compile-time only.
    // compileOnlyApi makes them visible to consumers' compilers without a runtime dep.
    compileOnlyApi(libs.jspecify)

    // ADR-005: Jackson is the JSON library. Implementation (not api) since
    // consumers see typed records, not Jackson types directly.
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// ADR-002 CI matrix: optionally run any Test task on a specific JDK
// while compilation stays pinned to --release 17. The flag is wired to
// every Test task (unit `test` + `integrationTest`) so on-demand and
// merge-to-main matrix runs cover the live API on JDK 17/21/25 too.
val testJdkProperty = providers.gradleProperty("testJdk").orNull
if (testJdkProperty != null) {
    val launcher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(testJdkProperty.toInt())
    }
    tasks.withType<Test>().configureEach {
        javaLauncher.set(launcher)
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Aggregate coverage across unit tests and integration tests. Opt-in: not
// wired into `check` so PR builds stay fast and don't require the IT secret.
// Invoke as `MARKETDATA_RUN_INTEGRATION_TESTS=true ./gradlew jacocoAggregateReport`.
tasks.register<JacocoReport>("jacocoAggregateReport") {
    description = "Generates a JaCoCo report aggregating unit + integration test coverage."
    group = "verification"

    dependsOn(tasks.test, integrationTestTask)

    sourceSets(sourceSets.main.get())
    executionData(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )

    reports {
        xml.required = true
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/aggregate/html")
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.xml")
    }
}

// Coverage ratchet (line coverage cannot drop more than 5 pp below main's last value) is enforced
// in CI — see .github/workflows/pull-request.yml and .github/scripts/check-coverage-delta.py.
//
// SDK requirements §15.3: 100% line coverage. Enforced locally and in CI by
// jacocoTestCoverageVerification (wired into `check`). The handful of genuinely untestable lines —
// network-only constructor paths, fail-safe catch blocks, TOCTOU retry edges — are isolated into
// members annotated @Generated, which JaCoCo excludes from the count (the annotation's simple name
// contains "Generated", the marker JaCoCo recognizes since 0.8.2). Each use carries a comment
// explaining why the member is unreachable from a hermetic unit test.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
    }
}

// ADR-003 / requirements §15: Maven Central publishing via Vanniktech.
//
// Publishes to the Sonatype Central Portal (central.sonatype.com).
// `automaticRelease = false` uploads the deployment but leaves it in the
// VALIDATED state for manual review/release (or drop) from the portal UI —
// the safe path for a first manual validation run.
//
// Upload + signing credentials are read from Gradle properties / env vars by
// the plugin (never hard-coded here):
//   - ORG_GRADLE_PROJECT_mavenCentralUsername / _mavenCentralPassword
//   - ORG_GRADLE_PROJECT_signingInMemoryKey / _signingInMemoryKeyPassword
//     (optionally _signingInMemoryKeyId)
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    coordinates(group.toString(), "marketdata-sdk-java", version.toString())
    pom {
        name.set("Market Data Java SDK")
        description.set("Java SDK for the Market Data API.")
        url.set("https://github.com/MarketDataApp/sdk-java")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/MarketDataApp/sdk-java/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("marketdata")
                name.set("Market Data")
                url.set("https://www.marketdata.app")
            }
        }
        scm {
            url.set("https://github.com/MarketDataApp/sdk-java")
            connection.set("scm:git:git://github.com/MarketDataApp/sdk-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/MarketDataApp/sdk-java.git")
        }
    }
}
