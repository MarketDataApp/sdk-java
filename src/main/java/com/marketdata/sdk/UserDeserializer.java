package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.User;
import java.io.IOException;

/**
 * Wire-format deserializer for {@link User}. The server uses HTTP-header-styled keys in the JSON
 * body ({@code "x-ratelimit-requests-remaining"} etc.); this class maps them to the record's
 * camelCase fields here rather than via {@code @JsonProperty} on the record, keeping all wire
 * coupling out of the public response type (ADR-007).
 *
 * <p>Missing fields default leniently — {@code 0} for ints, empty string for {@code
 * optionsDataPermissions}. The server always sends all three keys today, so a missing one is either
 * a backend regression or a partial response we'd rather not blow up on.
 */
final class UserDeserializer extends JsonDeserializer<User> {

  private static final String REMAINING_KEY = "x-ratelimit-requests-remaining";
  private static final String LIMIT_KEY = "x-ratelimit-requests-limit";
  private static final String OPTIONS_PERMS_KEY = "x-options-data-permissions";

  @Override
  public User deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    int remaining = root.path(REMAINING_KEY).asInt(0);
    int limit = root.path(LIMIT_KEY).asInt(0);
    String optionsPerms = root.path(OPTIONS_PERMS_KEY).asText("");
    return new User(remaining, limit, optionsPerms);
  }
}
