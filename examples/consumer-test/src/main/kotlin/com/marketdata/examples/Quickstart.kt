package com.marketdata.examples

import com.marketdata.sdk.MarketDataClient
import com.marketdata.sdk.exception.AuthenticationError
import com.marketdata.sdk.stocks.StockQuoteRequest

/**
 * The same SDK, from Kotlin. The Java API is designed to be idiomatic from Kotlin: the client is an
 * `AutoCloseable` (so `use {}` works), nullability is honored (no platform types), and the async
 * variants return `CompletableFuture`, which Kotlin interops with directly.
 *
 * Set `MARKETDATA_TOKEN` (env var or `.env`) before running.
 *
 * Run: `./gradlew runKotlinQuickstart`
 */
fun main() {
    // `use {}` closes the client when the block ends — the Kotlin equivalent of try-with-resources.
    MarketDataClient().use { client ->
        try {
            // Sync: blocks and returns the typed response.
            val quote = client.stocks().quote(StockQuoteRequest.of("AAPL")).values()[0]
            println("Sync:  ${quote.symbol()} last=${quote.last()}")

            // Async: returns a CompletableFuture. Join it, or attach a callback. (Kotlin consumers
            // using coroutines can `await()` it via kotlinx-coroutines-jdk8.)
            client.stocks()
                .quoteAsync(StockQuoteRequest.of("AAPL"))
                .thenAccept { resp -> println("Async: ${resp.values()[0].last()}") }
                .join()
        } catch (e: AuthenticationError) {
            println("Set MARKETDATA_TOKEN (env var or .env) to run this example.")
        }
    }
}
