package com.marketdata.sdk.options;

/**
 * Pricing model for {@code iv} and the greeks on a historical options request — the chain
 * endpoint's and the quotes endpoint's {@code ?model=} filter.
 *
 * <p>{@link #BSM} prices every contract (European and American). {@link #BJS}, {@link #CRR} and
 * {@link #BAW} price the American contracts; the European contracts keep {@link #BSM} regardless of
 * which of the three is named. If the filter is omitted entirely, American contracts default to
 * {@link #BJS} and European contracts to {@link #BSM}, and the server applies broker conventions on
 * top (a contract without a bid is priced from half its ask, a contract at or below the model's
 * lowest price gets the greeks of an option without time value, an expiring contract gets its
 * greeks at expiration, and delta is clamped to {@code [-1, 1]}). Naming a model explicitly — any
 * of the four — skips those conventions: a contract that would have received the zero-time-value
 * treatment gets {@code null} values instead, and delta is the model's own, unclamped.
 *
 * <p>Only meaningful on a historical request ({@code date} for the chain endpoint; {@code date},
 * {@code from} or {@code to} for the quotes endpoint) — the API rejects a request that names a
 * model without one of those with a 400. The filter is also plan-gated: a trial plan gets a 402.
 */
public enum PricingModel {
  /** Black-Scholes-Merton — prices every contract, European and American alike. */
  BSM("bsm"),
  /** Bjerksund-Stensland (2002) — American contracts; the default for them when unset. */
  BJS("bjs"),
  /** Cox-Ross-Rubinstein binomial tree — American contracts. */
  CRR("crr"),
  /** Barone-Adesi-Whaley — American contracts. */
  BAW("baw");

  private final String wireValue;

  PricingModel(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The lowercase token the API uses on the wire. */
  public String wireValue() {
    return wireValue;
  }
}
