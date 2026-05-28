package com.marketdata.sdk.options;

/**
 * Coarse-grained strike-range filter for the chain endpoint's {@code ?range=} parameter. Used
 * together with {@code strikeLimit} on {@link OptionsChainRequest} to ask for "the N strikes around
 * the in-the-money / out-of-the-money boundary".
 */
public enum StrikeRange {
  ITM("itm"),
  OTM("otm"),
  ALL("all");

  private final String wireValue;

  StrikeRange(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The lowercase token the API uses on the wire. */
  public String wireValue() {
    return wireValue;
  }
}
