package com.marketdata.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.ServiceStatus;
import java.io.IOException;
import java.util.List;

/**
 * Wire-format deserializer for {@link ApiStatus}. The server uses the API's standard
 * parallel-arrays shape: six equal-length arrays of column values plus the {@code "s"} envelope.
 * {@link ParallelArrays#zip} handles the structural validation; this class only declares which
 * columns are expected and how to materialize a {@link ServiceStatus} from one row.
 *
 * <p>Error envelopes ({@code s == "error"}), missing arrays, and mismatched lengths bubble up as
 * {@link JsonMappingException}, which the parent {@link JsonResponseParser} turns into a {@link
 * com.marketdata.sdk.exception.ParseError} with the response context attached.
 */
final class ApiStatusDeserializer extends JsonDeserializer<ApiStatus> {

  private static final List<String> FIELDS =
      List.of("service", "status", "online", "uptimePct30d", "uptimePct90d", "updated");

  @Override
  public ApiStatus deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode root = p.readValueAsTree();
    List<ServiceStatus> services =
        ParallelArrays.zip(
            p,
            root,
            FIELDS,
            row ->
                new ServiceStatus(
                    row.text("service"),
                    row.text("status"),
                    row.bool("online"),
                    row.dbl("uptimePct30d"),
                    row.dbl("uptimePct90d"),
                    MarketDataDates.marketTimeFromEpochSecond(row.lng("updated"))));
    return new ApiStatus(services);
  }
}
