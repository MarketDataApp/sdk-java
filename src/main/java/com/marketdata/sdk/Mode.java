package com.marketdata.sdk;

/**
 * Data-freshness tier requested for the response. Controlled via {@code ?mode=}.
 *
 * <ul>
 *   <li>{@link #LIVE} — current market data (default).
 *   <li>{@link #DELAYED} — exchange-delayed data, typically 15 minutes.
 *   <li>{@link #CACHED} — last cached snapshot; lowest cost, highest staleness.
 * </ul>
 */
public enum Mode {
  LIVE("live"),
  DELAYED("delayed"),
  CACHED("cached");

  private final String wireValue;

  Mode(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The value sent in the {@code ?mode=} query parameter. */
  public String wireValue() {
    return wireValue;
  }
}
