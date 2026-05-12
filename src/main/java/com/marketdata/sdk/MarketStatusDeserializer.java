package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.markets.DailyStatus;
import com.marketdata.sdk.markets.MarketStatus;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson deserializer for the {@code /v1/markets/status/} parallel-arrays wire format.
 *
 * <p>Wire shape (success):
 *
 * <pre>{@code
 * { "s": "ok",
 *   "date":   [1706745600, 1706832000, 1706918400],
 *   "status": ["open", "open", "closed"] }
 * }</pre>
 *
 * <p>Wire shape (no data, also returned for non-US countries by design):
 *
 * <pre>{@code
 * { "s": "no_data" }
 * }</pre>
 *
 * <p>The deserializer expands the parallel arrays into a list of {@link DailyStatus} (one per
 * index), normalizes the unix timestamps to {@link LocalDate} in US/Eastern (SDK requirements
 * §11.4), and represents {@code "no_data"} as an empty list.
 */
final class MarketStatusDeserializer extends JsonDeserializer<MarketStatus> {

  private static final ZoneId EASTERN = ZoneId.of("America/New_York");
  private static final String STATUS_OK = "ok";
  private static final String STATUS_NO_DATA = "no_data";

  @Override
  public MarketStatus deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    String s = root.path("s").asText("");

    if (STATUS_NO_DATA.equals(s)) {
      return new MarketStatus(List.of());
    }
    if (!STATUS_OK.equals(s)) {
      throw new IOException(
          "Unexpected status field in /markets/status response: '"
              + s
              + "' (expected 'ok' or 'no_data')");
    }

    JsonNode dates = root.path("date");
    JsonNode statuses = root.path("status");
    if (!dates.isArray() || !statuses.isArray()) {
      throw new IOException(
          "Malformed /markets/status response: expected 'date' and 'status' arrays");
    }
    if (dates.size() != statuses.size()) {
      throw new IOException(
          "Malformed /markets/status response: 'date' and 'status' arrays have different sizes ("
              + dates.size()
              + " vs "
              + statuses.size()
              + ")");
    }

    List<DailyStatus> days = new ArrayList<>(dates.size());
    for (int i = 0; i < dates.size(); i++) {
      LocalDate date = Instant.ofEpochSecond(dates.get(i).asLong()).atZone(EASTERN).toLocalDate();
      boolean open = "open".equalsIgnoreCase(statuses.get(i).asText());
      days.add(new DailyStatus(date, open));
    }
    return new MarketStatus(List.copyOf(days));
  }
}
