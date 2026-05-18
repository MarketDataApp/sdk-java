package com.marketdata.sdk;

/**
 * Date/time serialization format for response payloads. Controlled via {@code ?dateformat=}.
 *
 * <ul>
 *   <li>{@link #UNIX} — epoch seconds (default).
 *   <li>{@link #TIMESTAMP} — ISO-8601-style timestamp string.
 *   <li>{@link #SPREADSHEET} — Excel/Sheets-compatible serial date number.
 * </ul>
 */
public enum DateFormat {
  UNIX("unix"),
  TIMESTAMP("timestamp"),
  SPREADSHEET("spreadsheet");

  private final String wireValue;

  DateFormat(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The value sent in the {@code ?dateformat=} query parameter. */
  public String wireValue() {
    return wireValue;
  }
}
