package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.ParseError;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonResponseParserTest {

  /**
   * Build a parser pre-loaded with the utilities resource's wire-format module. The parser itself
   * is resource-agnostic (issue #9 fix); these tests exercise the deserializers shipped by {@link
   * UtilitiesResource}, so the registration that the production constructor performs is replicated
   * here.
   */
  private static JsonResponseParser parserWithUtilitiesModule() {
    JsonResponseParser p = new JsonResponseParser();
    p.registerModule(UtilitiesResource.wireFormatModule());
    return p;
  }

  private static HttpResponseEnvelope env(String body) {
    return new HttpResponseEnvelope(
        body.getBytes(),
        200,
        "test-request-id",
        HttpHeaders.of(Map.of(), (a, b) -> true),
        URI.create("http://localhost/headers/"));
  }

  @Test
  void parsesRequestHeadersFromFlatJsonObject() {
    JsonResponseParser parser = parserWithUtilitiesModule();

    RequestHeaders rh =
        parser.parse(
            env("{\"accept\":\"*/*\",\"cf-ray\":\"abc-123\",\"user-agent\":\"java/0\"}"),
            RequestHeaders.class);

    assertThat(rh.headers())
        .containsEntry("accept", "*/*")
        .containsEntry("cf-ray", "abc-123")
        .containsEntry("user-agent", "java/0");
  }

  @Test
  void emptyJsonObjectProducesEmptyHeaders() {
    JsonResponseParser parser = parserWithUtilitiesModule();
    RequestHeaders rh = parser.parse(env("{}"), RequestHeaders.class);
    assertThat(rh.headers()).isEmpty();
  }

  @Test
  void requestHeadersMapIsImmutable() {
    JsonResponseParser parser = parserWithUtilitiesModule();
    RequestHeaders rh = parser.parse(env("{\"a\":\"1\"}"), RequestHeaders.class);

    assertThatThrownBy(() -> rh.headers().put("hacked", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---------- User: hyphenated wire keys → camelCase record ----------

  @Test
  void parsesUserMappingHyphenatedKeysToCamelCase() {
    JsonResponseParser parser = parserWithUtilitiesModule();

    User u =
        parser.parse(
            env(
                "{\"x-ratelimit-requests-remaining\":5421,"
                    + "\"x-ratelimit-requests-limit\":100000,"
                    + "\"x-options-data-permissions\":\"OPRA data delayed 15 minutes\"}"),
            User.class);

    assertThat(u.requestsRemaining()).isEqualTo(5421);
    assertThat(u.requestsLimit()).isEqualTo(100000);
    assertThat(u.optionsDataPermissions()).isEqualTo("OPRA data delayed 15 minutes");
  }

  @Test
  void parsesUserWithEmptyOptionsPermissionsAsRealTimeMarker() {
    // Empty string is the server's convention for "real-time access"; the SDK preserves it
    // verbatim so consumers can detect realTime via `permissions.isEmpty()`.
    JsonResponseParser parser = parserWithUtilitiesModule();

    User u =
        parser.parse(
            env(
                "{\"x-ratelimit-requests-remaining\":10,"
                    + "\"x-ratelimit-requests-limit\":10,"
                    + "\"x-options-data-permissions\":\"\"}"),
            User.class);

    assertThat(u.optionsDataPermissions()).isEmpty();
  }

  @Test
  void missingUserNumericFieldRaisesParseError() {
    // Strict: a silent zero would mask backend regressions and surface later as a confusing
    // "quota apparently exhausted". Same policy as ParallelArrays.
    JsonResponseParser parser = parserWithUtilitiesModule();

    assertThatThrownBy(() -> parser.parse(env("{\"x-ratelimit-requests-limit\":500}"), User.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("missing or non-integer")
        .hasMessageContaining("x-ratelimit-requests-remaining");
  }

  @Test
  void userNumericFieldOfWrongTypeRaisesParseError() {
    // String "500" instead of integer 500 — strict rejection rather than Jackson's lax coercion.
    JsonResponseParser parser = parserWithUtilitiesModule();

    assertThatThrownBy(
            () ->
                parser.parse(
                    env(
                        "{\"x-ratelimit-requests-remaining\":\"5\","
                            + "\"x-ratelimit-requests-limit\":10,"
                            + "\"x-options-data-permissions\":\"\"}"),
                    User.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("non-integer")
        .hasMessageContaining("x-ratelimit-requests-remaining");
  }

  @Test
  void userMissingOptionsPermsRaisesParseError() {
    // The empty string is the legitimate "real-time access" marker — but the field must be
    // present as a JSON string. Absence is treated as a backend regression, not a default.
    JsonResponseParser parser = parserWithUtilitiesModule();

    assertThatThrownBy(
            () ->
                parser.parse(
                    env(
                        "{\"x-ratelimit-requests-remaining\":1,"
                            + "\"x-ratelimit-requests-limit\":2}"),
                    User.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("missing or non-string")
        .hasMessageContaining("x-options-data-permissions");
  }

  // ---------- ApiStatus: parallel-arrays wire format zipped into List<ServiceStatus> ----------

  @Test
  void parsesApiStatusByZippingParallelArrays() {
    // Canonical happy-path payload — six arrays of equal length plus the leading "s":"ok".
    String body =
        "{"
            + "\"s\":\"ok\","
            + "\"service\":[\"/v1/stocks/quotes/\",\"/v1/options/chain/\"],"
            + "\"status\":[\"online\",\"offline\"],"
            + "\"online\":[true,false],"
            + "\"uptimePct30d\":[1.0,0.9961],"
            + "\"uptimePct90d\":[0.99828,0.95],"
            + "\"updated\":[1734036832,1734036833]"
            + "}";

    ApiStatus status = parserWithUtilitiesModule().parse(env(body), ApiStatus.class);

    assertThat(status.services()).hasSize(2);
    ServiceStatus first = status.services().get(0);
    assertThat(first.service()).isEqualTo("/v1/stocks/quotes/");
    assertThat(first.status()).isEqualTo("online");
    assertThat(first.online()).isTrue();
    assertThat(first.uptimePct30d()).isEqualTo(1.0);
    assertThat(first.uptimePct90d()).isEqualTo(0.99828);
    assertThat(first.updated()).isEqualTo(MarketDataDates.marketTimeFromEpochSecond(1734036832L));

    ServiceStatus second = status.services().get(1);
    assertThat(second.service()).isEqualTo("/v1/options/chain/");
    assertThat(second.online()).isFalse();
    assertThat(second.uptimePct30d()).isEqualTo(0.9961);
  }

  @Test
  void parsesApiStatusWithEmptyArrays() {
    String body =
        "{\"s\":\"ok\",\"service\":[],\"status\":[],\"online\":[],"
            + "\"uptimePct30d\":[],\"uptimePct90d\":[],\"updated\":[]}";

    ApiStatus status = parserWithUtilitiesModule().parse(env(body), ApiStatus.class);

    assertThat(status.services()).isEmpty();
  }

  @Test
  void apiStatusServicesListIsImmutable() {
    String body =
        "{\"s\":\"ok\",\"service\":[\"a\"],\"status\":[\"online\"],\"online\":[true],"
            + "\"uptimePct30d\":[1.0],\"uptimePct90d\":[1.0],\"updated\":[0]}";
    ApiStatus status = parserWithUtilitiesModule().parse(env(body), ApiStatus.class);

    assertThatThrownBy(() -> status.services().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void apiStatusNoDataEnvelopeProducesEmptyApiStatus() {
    // The backend returns {"s":"no_data"} (with HTTP 404) when a query has no matches. The
    // parser must not explode on the absent arrays — the response wrapper relies on the typed
    // model being constructable so consumers can branch on isNoData() while still calling
    // .data().services().
    String body = "{\"s\":\"no_data\"}";

    ApiStatus status = parserWithUtilitiesModule().parse(env(body), ApiStatus.class);

    assertThat(status.services()).isEmpty();
  }

  @Test
  void apiStatusNoDataEnvelopeWithMetadataFieldsStillEmpty() {
    // Some backend handlers attach hints to the no_data envelope (nextTime, prevTime, errmsg);
    // those siblings must not perturb the empty result.
    String body =
        "{\"s\":\"no_data\","
            + "\"nextTime\":null,\"prevTime\":null,"
            + "\"errmsg\":\"Market closed on this date.\"}";

    ApiStatus status = parserWithUtilitiesModule().parse(env(body), ApiStatus.class);

    assertThat(status.services()).isEmpty();
  }

  @Test
  void apiStatusServerSideErrorBecomesParseError() {
    // `s: "error"` is the server's soft-error path — the body is valid JSON but doesn't carry
    // the usable arrays. Surface as ParseError so it doesn't masquerade as an empty success.
    String body = "{\"s\":\"error\",\"errmsg\":\"database connection refused\"}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("database connection refused");
  }

  @Test
  void apiStatusMismatchedArrayLengthsBecomeParseError() {
    String body =
        "{\"s\":\"ok\","
            + "\"service\":[\"a\",\"b\"],"
            + "\"status\":[\"online\"]," // 1 vs 2
            + "\"online\":[true,false],"
            + "\"uptimePct30d\":[1.0,1.0],"
            + "\"uptimePct90d\":[1.0,1.0],"
            + "\"updated\":[0,0]}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("mismatched lengths");
  }

  @Test
  void apiStatusNonArrayFieldBecomesParseError() {
    // Field exists in the response but is not an array (e.g. a string). The "missing or
    // non-array" guard treats this as malformed.
    String body =
        "{\"s\":\"ok\","
            + "\"service\":\"not-an-array\","
            + "\"status\":[\"online\"],"
            + "\"online\":[true],"
            + "\"uptimePct30d\":[1.0],"
            + "\"uptimePct90d\":[1.0],"
            + "\"updated\":[0]}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("missing or non-array")
        .hasMessageContaining("service");
  }

  @Test
  void apiStatusMissingArrayBecomesParseError() {
    // No `online` array — could happen if a backend refactor drops a field; better to fail
    // loudly than silently default booleans to false for every row.
    String body =
        "{\"s\":\"ok\","
            + "\"service\":[\"a\"],"
            + "\"status\":[\"online\"],"
            + "\"uptimePct30d\":[1.0],"
            + "\"uptimePct90d\":[1.0],"
            + "\"updated\":[0]}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("missing or non-array")
        .hasMessageContaining("online");
  }

  @Test
  void apiStatusNullCellInOnlineArrayBecomesParseError() {
    // Real-world regression scenario: the backend ships a build where `online` is sometimes
    // null instead of a boolean. Before the strict-cell validation, this silently became
    // online=false for every row → StatusCache marks services as offline → SDK blocks retries
    // across the board. The strict accessor must surface the malformed cell as ParseError.
    String body =
        "{\"s\":\"ok\","
            + "\"service\":[\"a\",\"b\"],"
            + "\"status\":[\"online\",\"online\"],"
            + "\"online\":[true,null],"
            + "\"uptimePct30d\":[1.0,1.0],"
            + "\"uptimePct90d\":[1.0,1.0],"
            + "\"updated\":[0,0]}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("null cell")
        .hasMessageContaining("online");
  }

  @Test
  void apiStatusWrongTypeInUptimeArrayBecomesParseError() {
    // The backend swaps a number for a string (e.g. "1.0" instead of 1.0). Strict mode rejects
    // it rather than relying on Jackson's lax string→number coercion.
    String body =
        "{\"s\":\"ok\","
            + "\"service\":[\"a\"],"
            + "\"status\":[\"online\"],"
            + "\"online\":[true],"
            + "\"uptimePct30d\":[\"1.0\"]," // string instead of number
            + "\"uptimePct90d\":[1.0],"
            + "\"updated\":[0]}";

    assertThatThrownBy(() -> parserWithUtilitiesModule().parse(env(body), ApiStatus.class))
        .isInstanceOf(ParseError.class)
        .hasMessageContaining("expected number")
        .hasMessageContaining("uptimePct30d");
  }

  @Test
  void malformedJsonRaisesParseErrorCarryingResponseContext() {
    JsonResponseParser parser = parserWithUtilitiesModule();

    assertThatThrownBy(() -> parser.parse(env("{not-json"), RequestHeaders.class))
        .isInstanceOf(ParseError.class)
        .satisfies(
            t -> {
              ParseError err = (ParseError) t;
              assertThat(err.getRequestUrl()).isEqualTo("http://localhost/headers/");
              assertThat(err.getStatusCode()).isEqualTo(200);
              assertThat(err.getRequestId()).isEqualTo("test-request-id");
              assertThat(err.getCause()).isNotNull();
            });
  }

  // ---------- §9 / ADR-007: parser is resource-agnostic ----------

  /**
   * Regression guard: a bare {@link JsonResponseParser} (no modules registered) must NOT know how
   * to deserialize {@link RequestHeaders} or any other resource type. If a future change
   * reintroduces hardcoded deserializers in the parser's constructor, this test catches it.
   */
  @Test
  void bareParserDoesNotKnowResourceDeserializers() {
    JsonResponseParser bare = new JsonResponseParser();

    // RequestHeaders requires the custom deserializer; without it Jackson's default record
    // mapping fails for the wire shape ({"accept":"*/*",...}) because the record has no
    // matching property names. Surfaces as ParseError per the parser's contract.
    assertThatThrownBy(() -> bare.parse(env("{\"accept\":\"*/*\"}"), RequestHeaders.class))
        .isInstanceOf(ParseError.class);
  }
}
