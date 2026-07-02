package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.options.OptionsExpirations;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-format deserializer for {@link OptionsExpirations}. The endpoint's body mixes a parallel
 * array ({@code expirations}) with a scalar metadata field ({@code updated}) at the top level,
 * which is the shape {@link ParallelArrays#listDeserializer} cannot express (the wrapper there only
 * receives the row list). Envelope handling mirrors {@link ParallelArrays}:
 *
 * <ul>
 *   <li>{@code "s":"error"} → {@link JsonMappingException} carrying {@code errmsg}.
 *   <li>{@code "s":"no_data"} → empty list, {@code updated} left null — the API omits the data
 *       fields in that envelope.
 *   <li>otherwise → strict field validation; missing or wrong-typed {@code expirations} / {@code
 *       updated} raises {@link JsonMappingException}.
 * </ul>
 *
 * <p>Per §3 the deserializer accepts whichever {@code dateformat} the consumer asked the API for —
 * {@code unix}, {@code timestamp}, or {@code spreadsheet} — and converts to native types through
 * {@link MarketDataDates#parseDateField} / {@link MarketDataDates#parseTimestampField}. That way
 * the typed {@link OptionsExpirations#data} surface is uniform regardless of wire format.
 */
final class OptionsExpirationsDeserializer extends JsonDeserializer<OptionsExpirations> {

  private static final String ENVELOPE_STATUS = "s";
  private static final String ENVELOPE_ERRMSG = "errmsg";
  private static final String ENVELOPE_ERROR = "error";
  private static final String ENVELOPE_NO_DATA = "no_data";
  private static final String EXPIRATIONS_KEY = "expirations";
  private static final String UPDATED_KEY = "updated";

  @Override
  public OptionsExpirations deserialize(JsonParser p, DeserializationContext ctxt)
      throws IOException {
    JsonNode root = p.readValueAsTree();
    String envelopeStatus = root.path(ENVELOPE_STATUS).asText("");
    if (ENVELOPE_ERROR.equals(envelopeStatus)) {
      throw new JsonMappingException(
          p,
          "API responded with error: "
              + LogSafe.sanitize(root.path(ENVELOPE_ERRMSG).asText("(no errmsg field)")));
    }
    if (ENVELOPE_NO_DATA.equals(envelopeStatus)) {
      return new OptionsExpirations(List.of(), null);
    }

    JsonNode expsNode = root.get(EXPIRATIONS_KEY);
    if (expsNode == null || !expsNode.isArray()) {
      throw new JsonMappingException(p, "missing or non-array field: " + EXPIRATIONS_KEY);
    }
    List<ZonedDateTime> dates = new ArrayList<>(expsNode.size());
    for (int i = 0; i < expsNode.size(); i++) {
      // Expirations are calendar dates on the wire; lift each to a midnight market-zone moment so
      // the consumer always sees a ZonedDateTime — the SDK's canonical market-timestamp type.
      LocalDate cellDate =
          MarketDataDates.parseDateField(p, expsNode.get(i), EXPIRATIONS_KEY + "[" + i + "]");
      dates.add(cellDate.atStartOfDay(MarketDataDates.MARKET_ZONE));
    }

    ZonedDateTime updated =
        MarketDataDates.parseTimestampField(p, root.get(UPDATED_KEY), UPDATED_KEY);
    return new OptionsExpirations(dates, updated);
  }
}
