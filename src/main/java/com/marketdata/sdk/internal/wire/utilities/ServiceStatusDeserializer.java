package com.marketdata.sdk.internal.wire.utilities;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.ServiceHealth;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson deserializer for the {@code GET /status/} parallel-arrays wire format.
 *
 * <p>Wire shape (success):
 *
 * <pre>{@code
 * { "s": "ok",
 *   "service":      ["/v1/funds/candles/", "/v1/stocks/quotes/"],
 *   "status":       ["online", "online"],
 *   "online":       [true, true],
 *   "uptimePct30d": [1.0, 0.998],
 *   "uptimePct90d": [1.0, 0.997],
 *   "updated":      [1734036832, 1734036832] }
 * }</pre>
 *
 * <p>The deserializer expands the parallel arrays into a list of {@link ServiceHealth}, one per
 * service, in input order. {@code "no_data"} produces an empty list.
 */
public final class ServiceStatusDeserializer extends JsonDeserializer<ServiceStatus> {

  private static final String STATUS_OK = "ok";
  private static final String STATUS_NO_DATA = "no_data";

  @Override
  public ServiceStatus deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    String s = root.path("s").asText("");

    if (STATUS_NO_DATA.equals(s)) {
      return new ServiceStatus(List.of());
    }
    if (!STATUS_OK.equals(s)) {
      throw new IOException(
          "Unexpected status field in /status response: '" + s + "' (expected 'ok' or 'no_data')");
    }

    JsonNode services = root.path("service");
    JsonNode statuses = root.path("status");
    JsonNode onlines = root.path("online");
    JsonNode uptime30 = root.path("uptimePct30d");
    JsonNode uptime90 = root.path("uptimePct90d");
    JsonNode updated = root.path("updated");

    if (!services.isArray() || !statuses.isArray() || !onlines.isArray()) {
      throw new IOException(
          "Malformed /status response: expected 'service', 'status', and 'online' arrays");
    }
    int n = services.size();
    if (statuses.size() != n
        || onlines.size() != n
        || uptime30.size() != n
        || uptime90.size() != n
        || updated.size() != n) {
      throw new IOException(
          "Malformed /status response: parallel-array sizes don't match (" + n + " expected)");
    }

    List<ServiceHealth> result = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      result.add(
          new ServiceHealth(
              services.get(i).asText(),
              onlines.get(i).asBoolean(),
              statuses.get(i).asText(),
              uptime30.get(i).asDouble(),
              uptime90.get(i).asDouble(),
              Instant.ofEpochSecond(updated.get(i).asLong())));
    }
    return new ServiceStatus(List.copyOf(result));
  }
}
