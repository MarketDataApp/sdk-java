package com.marketdata.sdk.options;

/**
 * Option side — call or put. Used both as the typed enum form of the chain endpoint's {@code
 * ?side=} filter and as the typed surface for {@code OptionQuote.side()} (today still a plain
 * {@code String} — pending an SDK-wide migration to the enum).
 */
public enum OptionSide {
  CALL("call"),
  PUT("put");

  private final String wireValue;

  OptionSide(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The lowercase token the API uses on the wire. */
  public String wireValue() {
    return wireValue;
  }
}
