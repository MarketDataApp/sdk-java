package com.marketdata.sdk.options;

/**
 * Response shape for {@code GET /v1/options/lookup/{userInput}/} — a single OCC-formatted option
 * symbol resolved from a human-readable description (e.g. {@code "AAPL 7/26/23 $200 Call"} → {@code
 * "AAPL230726C00200000"}).
 *
 * <p>The wire shape is a flat single-value object ({@code {"s":"ok","optionSymbol":"..."}}), not
 * the parallel-arrays envelope used by chain/quotes/expirations — that is why this record is
 * decoded by a hand-written {@link com.marketdata.sdk.OptionsLookupDeserializer} instead of the
 * {@code ParallelArrays.listDeserializer} factory.
 *
 * @param optionSymbol the OCC option symbol the user input resolves to. Always non-empty when
 *     present; the deserializer rejects a missing or non-textual {@code optionSymbol} as a {@link
 *     com.marketdata.sdk.exception.ParseError}.
 */
public record OptionsLookup(String optionSymbol) {}
