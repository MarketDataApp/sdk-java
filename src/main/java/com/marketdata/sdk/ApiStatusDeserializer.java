package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-format deserializer for {@link ApiStatus}. The server uses the API's standard
 * parallel-arrays shape: {@code service}, {@code status}, {@code online}, {@code uptimePct30d},
 * {@code uptimePct90d}, {@code updated} are six arrays of equal length. This class zips them into a
 * list of {@link ServiceStatus} records so consumers iterate naturally.
 *
 * <p>Error cases — {@code s == "error"} payloads, missing arrays, mismatched lengths — bubble up as
 * {@link JsonMappingException}, which the parent {@link JsonResponseParser} turns into a {@link
 * com.marketdata.sdk.exception.ParseError} with the response context attached.
 */
final class ApiStatusDeserializer extends JsonDeserializer<ApiStatus> {

  private static final String S = "s";
  private static final String ERRMSG = "errmsg";
  private static final String SERVICE = "service";
  private static final String STATUS = "status";
  private static final String ONLINE = "online";
  private static final String UPTIME_30 = "uptimePct30d";
  private static final String UPTIME_90 = "uptimePct90d";
  private static final String UPDATED = "updated";

  @Override
  public ApiStatus deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();

    String s = root.path(S).asText("");
    if ("error".equals(s)) {
      String errmsg = root.path(ERRMSG).asText("(no errmsg field)");
      throw new JsonMappingException(p, "API status reported error: " + errmsg);
    }

    JsonNode services = requireArray(p, root, SERVICE);
    JsonNode statuses = requireArray(p, root, STATUS);
    JsonNode onlines = requireArray(p, root, ONLINE);
    JsonNode up30 = requireArray(p, root, UPTIME_30);
    JsonNode up90 = requireArray(p, root, UPTIME_90);
    JsonNode updated = requireArray(p, root, UPDATED);

    int n = services.size();
    if (statuses.size() != n
        || onlines.size() != n
        || up30.size() != n
        || up90.size() != n
        || updated.size() != n) {
      throw new JsonMappingException(
          p,
          "API status arrays have mismatched lengths: service="
              + n
              + ", status="
              + statuses.size()
              + ", online="
              + onlines.size()
              + ", uptimePct30d="
              + up30.size()
              + ", uptimePct90d="
              + up90.size()
              + ", updated="
              + updated.size());
    }

    List<ServiceStatus> rows = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      rows.add(
          new ServiceStatus(
              services.get(i).asText(""),
              statuses.get(i).asText(""),
              onlines.get(i).asBoolean(false),
              up30.get(i).asDouble(0.0),
              up90.get(i).asDouble(0.0),
              Instant.ofEpochSecond(updated.get(i).asLong(0L))));
    }
    return new ApiStatus(rows);
  }

  private static JsonNode requireArray(JsonParser p, JsonNode root, String field)
      throws JsonMappingException {
    JsonNode node = root.get(field);
    if (node == null || !node.isArray()) {
      throw new JsonMappingException(p, "API status missing or non-array field: " + field);
    }
    return node;
  }
}
