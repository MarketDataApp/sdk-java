package com.marketdata.sdk.options;

/**
 * The Black-Scholes greeks an option quote can carry. Used by {@link OptionQuote#presentGreeks()}
 * and {@link OptionQuote#greek(Greek)} to inspect which model-derived sensitivities are present on
 * a given row (they may be absent on illiquid contracts, or — for {@code rho} — omitted by the
 * feed).
 *
 * <p>Implied volatility ({@code iv}) is deliberately <em>not</em> here: it is a volatility, not a
 * sensitivity/greek. Read it via {@link OptionQuote#iv()}.
 */
public enum Greek {
  DELTA,
  GAMMA,
  THETA,
  VEGA,
  RHO
}
