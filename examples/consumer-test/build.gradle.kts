plugins {
  application
  kotlin("jvm") version "2.1.0"
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.marketdata:marketdata-sdk-java:0.1.0-SNAPSHOT")
}

// Default `./gradlew run` lands on the stocks resource example (live API).
application {
  mainClass = "com.marketdata.examples.resources.StocksExample"
}

// Each example gets its own JavaExec task in the "examples" group so
// `./gradlew tasks --group examples` lists them all.
//
// Resource examples hit the LIVE API (need MARKETDATA_TOKEN). Cross-cutting examples that need
// deterministic behavior (concurrency, retry, errors) drive a local mock server — start it with
// `cd ../mock-server && ./run.sh`.
val examples = mapOf(
  // --- resources: the first calls per resource ---
  "runUtilities" to ("com.marketdata.examples.resources.UtilitiesExample" to
    "utilities: API health, account quota, request echo (live)."),
  "runStocks" to ("com.marketdata.examples.resources.StocksExample" to
    "stocks: candles, quote, batch quotes (live)."),
  "runOptions" to ("com.marketdata.examples.resources.OptionsExample" to
    "options: lookup, expirations, chain, quote (live)."),
  "runFunds" to ("com.marketdata.examples.resources.FundsExample" to
    "funds: NAV candles (live)."),
  "runMarkets" to ("com.marketdata.examples.resources.MarketsExample" to
    "markets: open/closed calendar (live)."),
  // --- resources: less common parameters ---
  "runStocksAdvanced" to ("com.marketdata.examples.resources.StocksAdvancedExample" to
    "stocks: universal params, windows, columns, CSV, rate limit (live)."),
  "runOptionsAdvanced" to ("com.marketdata.examples.resources.OptionsAdvancedExample" to
    "options: sealed filters, fan-out, columns, CSV (live)."),
  "runFundsAdvanced" to ("com.marketdata.examples.resources.FundsAdvancedExample" to
    "funds: date windows, columns, CSV (live)."),
  "runMarketsAdvanced" to ("com.marketdata.examples.resources.MarketsAdvancedExample" to
    "markets: windows, country, columns, CSV (live)."),
  // --- common: cross-cutting behavior and configuration ---
  "runSyncVsAsync" to ("com.marketdata.examples.common.SyncVsAsyncExample" to
    "sync vs async, parallel fan-out (live)."),
  "runConcurrency" to ("com.marketdata.examples.common.ConcurrentRequestsExample" to
    "fan-out async + 50-permit concurrency cap, observed. Needs mock server."),
  "runRetry" to ("com.marketdata.examples.common.RetryAndBackoffExample" to
    "automatic retry + backoff + Retry-After. Needs mock server."),
  "runErrors" to ("com.marketdata.examples.common.ErrorHandlingExample" to
    "the sealed exception hierarchy and how to handle it. Needs mock server."),
  "runConfiguration" to ("com.marketdata.examples.common.ConfigurationExample" to
    "constructors, config cascade, token redaction, fail-fast validation (offline)."),
  "runResponseFormats" to ("com.marketdata.examples.common.ResponseFormatsExample" to
    "the response wrapper: data, metadata, formats, raw body, saveToFile (live)."),
  // --- Kotlin ---
  "runKotlinQuickstart" to ("com.marketdata.examples.QuickstartKt" to
    "the same SDK from Kotlin: sync + async (live).")
)

examples.forEach { (taskName, app) ->
  val (mainClassName, taskDescription) = app
  tasks.register<JavaExec>(taskName) {
    group = "examples"
    description = taskDescription
    mainClass = mainClassName
    classpath = sourceSets["main"].runtimeClasspath
    // Inherit stdio so the example's println output is visible and matches what a real consumer
    // would see running their own app.
    standardInput = System.`in`
  }
}
