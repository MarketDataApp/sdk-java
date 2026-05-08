/**
 * Sealed exception hierarchy thrown by the SDK.
 *
 * <p>The {@link com.marketdata.sdk.exception.MarketDataException} root is sealed so consumer {@code
 * switch} statements over the known subtypes are compiler-checked for exhaustiveness. Adding a new
 * subtype is a breaking change.
 */
@NullMarked
package com.marketdata.sdk.exception;

import org.jspecify.annotations.NullMarked;
