package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.User;
import java.io.IOException;

/**
 * Wire-format deserializer for {@link User}. The server uses HTTP-header-styled keys in the JSON
 * body ({@code "x-ratelimit-requests-remaining"} etc.); this class maps them to the record's
 * camelCase fields here rather than via {@code @JsonProperty} on the record, keeping all wire
 * coupling out of the public response type (ADR-007).
 *
 * <p>Strict by default — same reasoning as {@link ParallelArrays}: a silent default for a missing
 * numeric field would hide server bugs at the worst time (e.g. construction-time
 * validateOnStartup), surfacing later as "quota apparently exhausted" with no breadcrumb. The
 * empty string is the server's legitimate signal for "real-time options access" so {@code
 * optionsDataPermissions} only requires that the field be a JSON string, not that it be non-empty.
 */
final class UserDeserializer extends JsonDeserializer<User> {

  private static final String REMAINING_KEY = "x-ratelimit-requests-remaining";
  private static final String LIMIT_KEY = "x-ratelimit-requests-limit";
  private static final String OPTIONS_PERMS_KEY = "x-options-data-permissions";

  @Override
  public User deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    int remaining = readInt(p, root, REMAINING_KEY);
    int limit = readInt(p, root, LIMIT_KEY);
    String optionsPerms = readString(p, root, OPTIONS_PERMS_KEY);
    return new User(remaining, limit, optionsPerms);
  }

  private static int readInt(JsonParser p, JsonNode root, String key) throws JsonMappingException {
    JsonNode node = root.get(key);
    if (node == null || !node.isIntegralNumber()) {
      throw new JsonMappingException(p, "missing or non-integer field: " + key);
    }
    return node.asInt();
  }

  private static String readString(JsonParser p, JsonNode root, String key)
      throws JsonMappingException {
    JsonNode node = root.get(key);
    if (node == null || !node.isTextual()) {
      throw new JsonMappingException(p, "missing or non-string field: " + key);
    }
    return node.asText();
  }
}
