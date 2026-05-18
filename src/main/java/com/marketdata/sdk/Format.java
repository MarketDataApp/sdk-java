package com.marketdata.sdk;

/**
 * Wire response format negotiated via the {@code ?format=} query parameter.
 *
 * <p><strong>Package-private by design.</strong> SDK consumers never reference this enum directly —
 * resource façades expose a method per format (e.g. {@code stocks.candles(...)} returns a decoded
 * record from a JSON response; {@code stocks.candlesAsCsv(...)} returns the raw CSV). That keeps
 * the format choice surfaced as a method selection rather than a parameter the user has to import
 * {@code Format} for.
 *
 * <p>Why {@link #HTML} is here even though the server doesn't return it today: the SDK is supposed
 * to be ready. Plumbing for {@code text/html} responses lives in the transport pipeline — Accept
 * header, {@code ?format=html}, and round-trip-through-{@link HttpResponseEnvelope} — so the day an
 * endpoint flips it on, the only change required is a resource façade exposing a matching {@code
 * ...AsHtml(...)} method. No transport edits.
 *
 * <p>The server's renderer set today is JSON + CSV; {@code ?format=html} is currently a no-op
 * server-side (the response falls back to the default renderer). Internal callers that pass {@link
 * #HTML} should expect that until the server lights it up.
 */
enum Format {
  JSON("json", "application/json"),
  CSV("csv", "text/csv"),
  HTML("html", "text/html");

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
