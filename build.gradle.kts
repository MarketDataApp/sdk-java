plugins {
    `java-library`
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.vanniktech.publish)
}

group = "com.marketdata"
version = "0.1.0-SNAPSHOT"

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
val integrationTest by sourceSets.creating

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

    // ADR-002 CI matrix: optionally run tests on a specific JDK while
    // compilation stays pinned to --release 17. The CI workflow passes
    // -PtestJdk=17|21|25; locally you can do ./gradlew test -PtestJdk=21
    // (Gradle will provision the JDK via the foojay resolver if missing).
    val testJdk = providers.gradleProperty("testJdk").orNull
    if (testJdk != null) {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(testJdk.toInt())
            }
        )
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Coverage ratchet (line coverage cannot drop more than 5 pp below
// main's last value) is enforced in CI — see .github/workflows/pull-request.yml
// and .github/scripts/check-coverage-delta.py. Not enforced locally so that
// dev iteration isn't blocked while coverage is in flux.

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
// Coordinates and POM metadata below are placeholders — fill in before
// the first publication.
mavenPublishing {
    coordinates(group.toString(), "marketdata-sdk-java", version.toString())
    pom {
        name.set("Market Data Java SDK")
        description.set("Java SDK for the Market Data API.")
        // TODO: set url, scm, license, developers before publishing.
    }
}
