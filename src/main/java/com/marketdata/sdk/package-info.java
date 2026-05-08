/**
 * Market Data Java SDK — public API root.
 *
 * <p>This package hosts both the public API surface ({@link com.marketdata.sdk.MarketDataClient},
 * {@link com.marketdata.sdk.RateLimits}, and the resource façades) and every package-private
 * internal class (configuration cascade, env-var keys, token redaction, version detection, and the
 * HTTP/wire-format infrastructure). Per ADR-007, the "internal" boundary is enforced by Java's
 * package-private visibility: types not meant for consumers omit the {@code public} modifier so the
 * consumer's compiler simply cannot reference them.
 *
 * <p>{@code @NullMarked} applies at the package level — every type, parameter, return, and field is
 * non-null by default. Mark nullable items explicitly with {@link
 * org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.marketdata.sdk;

import org.jspecify.annotations.NullMarked;
