plugins {
  application
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

dependencies {
  implementation("com.marketdata:marketdata-sdk-java:0.1.0-SNAPSHOT")
}

// Default `./gradlew run` lands on the live-API smoke. The other apps are
// reachable via the named tasks below — each one is its own self-contained
// scenario walk-through.
application {
  mainClass = "com.marketdata.consumer.LiveSmokeApp"
}

// Each demo gets its own JavaExec task in the same Gradle group so
// `./gradlew tasks --group "consumer demos"` lists them all.
val demoApps = mapOf(
  "runQuickstart" to ("com.marketdata.consumer.QuickstartApp" to
    "Idiomatic per-resource usage. Grows as new resources land."),
  "runLive" to ("com.marketdata.consumer.LiveSmokeApp" to
    "Live API smoke (needs MARKETDATA_TOKEN)."),
  "runDemoConfig" to ("com.marketdata.consumer.DemoAndConfigApp" to
    "Demo mode, configuration cascade, validation. Needs mock server."),
  "runExceptions" to ("com.marketdata.consumer.ExceptionsApp" to
    "Round-trip each MarketDataException subtype. Needs mock server."),
  "runRetry" to ("com.marketdata.consumer.RetryBehaviorApp" to
    "Retry policy, Retry-After header, preflight gate. Needs mock server."),
  "runResponse" to ("com.marketdata.consumer.ResponseFeaturesApp" to
    "MarketDataResponse surface: predicates, isNoData, json, saveToFile, toString. Needs mock server."),
  "runConcurrency" to ("com.marketdata.consumer.ConcurrencyApp" to
    "§12 / ADR-007: 50-permit semaphore observed end-to-end. Needs mock server."),
  "runOptions" to ("com.marketdata.consumer.OptionsApp" to
    "Full options surface: every endpoint + all params, CSV facet, columns projection, Option A. Needs mock server."),
  "runStocks" to ("com.marketdata.consumer.StocksApp" to
    "Full stocks surface: candles/quote/quotes/prices/news/earnings + all params, CSV facet, columns projection, Option A. Needs mock server."),
  "runMarkets" to ("com.marketdata.consumer.MarketsApp" to
    "Full markets surface: status + all params (open/closed calendar, null cells), CSV facet, columns projection, Option A. Needs mock server.")
)

demoApps.forEach { (taskName, app) ->
  val (mainClassName, taskDescription) = app
  tasks.register<JavaExec>(taskName) {
    group = "consumer demos"
    description = taskDescription
    mainClass = mainClassName
    classpath = sourceSets["main"].runtimeClasspath
    // Inherit stdio so the demo's println output is visible in the console
    // and matches what a real consumer would see when they run their own app.
    standardInput = System.`in`
  }
}

