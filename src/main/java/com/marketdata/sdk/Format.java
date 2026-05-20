package com.marketdata.sdk;

/**
 * Wire response format negotiated via the {@code ?format=} query parameter.
 *
 * <p><strong>Package-private by design.</strong> SDK consumers never reference this enum directly —
 * resource façades expose a method per format (e.g. {@code stocks.candles(...)} returns a decoded
 * record from a JSON response; {@code stocks.candlesAsCsv(...)} returns the raw CSV). That keeps
 * the format choice surfaced as a method selection rather than a parameter the user has to import
 * {@code Format} for.
 */
enum Format {
  JSON("json", "application/json"),
  CSV("csv", "text/csv");

  private final String wireValue;
  private final String mediaType;

  Format(String wireValue, String mediaType) {
    this.wireValue = wireValue;
    this.mediaType = mediaType;
  }

  /** The value sent in the {@code ?format=} query parameter. */
  String wireValue() {
    return wireValue;
  }

  /** The media type sent in the {@code Accept} request header. */
  String mediaType() {
    return mediaType;
  }
}
