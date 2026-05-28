package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.options.ExpirationStrikes;
import com.marketdata.sdk.options.OptionsStrikes;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Wire-format deserializer for {@link OptionsStrikes}. Unlike every other endpoint, the strikes
 * body uses <em>dynamic</em> top-level keys — one per expiration date — alongside the fixed {@code
 * s} envelope and {@code updated} timestamp. {@link ParallelArrays} cannot express this shape; the
 * deserializer iterates the root's fields and treats any key that parses as an ISO date ({@code
 * "yyyy-MM-dd"}) as an expiration entry. Envelope handling mirrors the rest of the SDK:
 *
 * <ul>
 *   <li>{@code "s":"error"} → {@link JsonMappingException} with {@code errmsg}.
 *   <li>{@code "s":"no_data"} → empty list, {@code updated} left null (the API typically attaches
 *       {@code nextTime} / {@code prevTime} hints in that envelope; this deserializer ignores them
 *       because they aren't part of the typed surface).
 *   <li>otherwise → strict validation: missing {@code updated}, missing/wrong-typed strike values,
 *       or unrecognized non-date keys all raise {@link JsonMappingException}.
 * </ul>
 */
final class OptionsStrikesDeserializer extends JsonDeserializer<OptionsStrikes> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_ERROR = "error";
  private static final String ENVELOPE_NO_DATA = "no_data";
  private static final String UPDATED_KEY = "updated";

  @Override
  public OptionsStrikes deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    String envelopeStatus = root.path(ENVELOPE_STATUS).asText("");
    if (ENVELOPE_ERROR.equals(envelopeStatus)) {
      throw new JsonMappingException(
          p, "API responded with error: " + root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)"));
    }
    if (ENVELOPE_NO_DATA.equals(envelopeStatus)) {
      return new OptionsStrikes(List.of(), null);
    }

    ZonedDateTime updated =
        MarketDataDates.parseTimestampField(p, root.get(UPDATED_KEY), UPDATED_KEY);

    List<ExpirationStrikes> rows = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> it = root.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> entry = it.next();
      String key = entry.getKey();
      if (ENVELOPE_STATUS.equals(key) || UPDATED_KEY.equals(key)) {
        continue;
      }
      LocalDate expirationDate;
      try {
        expirationDate = LocalDate.parse(key);
      } catch (DateTimeParseException e) {
        // The endpoint is documented as `s` + `updated` + dynamic ISO-date keys; an unrecognized
        // key signals either a server bug or a forward-compatible extension. Strict-by-default
        // surfaces it.
        throw new JsonMappingException(p, "unrecognized top-level key: " + key);
      }
      JsonNode arr = entry.getValue();
      if (!arr.isArray()) {
        throw new JsonMappingException(p, "non-array value for expiration " + key);
      }
      List<Double> strikes = new ArrayList<>(arr.size());
      for (int i = 0; i < arr.size(); i++) {
        JsonNode cell = arr.get(i);
        if (!cell.isNumber()) {
          throw new JsonMappingException(
              p, "non-numeric strike at " + key + "[" + i + "]: " + cell.asText());
        }
        strikes.add(cell.asDouble());
      }
      rows.add(
          new ExpirationStrikes(expirationDate.atStartOfDay(MarketDataDates.MARKET_ZONE), strikes));
    }

    return new OptionsStrikes(rows, updated);
  }
}
