package com.marketdata.sdk.utilities;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.marketdata.sdk.internal.wire.utilities.ServiceStatusDeserializer;
import java.util.List;

/**
 * Result of a {@code GET /status/} call: per-service health for everything the API monitors.
 *
 * <p>The wire format is the same parallel-arrays shape used by other parallel-arrays endpoints
 * (each field is an array indexed by service); the SDK expands it into one {@link ServiceHealth}
 * per service via a custom Jackson deserializer (ADR-005).
 *
 * <p>Per SDK requirements §9.5 this endpoint also feeds the retry workflow's status cache — the SDK
 * uses it internally to decide whether to keep retrying when the API is reporting itself offline.
 * That wiring is deferred until the retry layer lands.
 *
 * @param services per-service health, in the order returned by the API; empty if the API reported
 *     {@code "no_data"}
 */
@JsonDeserialize(using = ServiceStatusDeserializer.class)
public record ServiceStatus(List<ServiceHealth> services) {

  public boolean allOnline() {
    return !services.isEmpty() && services.stream().allMatch(ServiceHealth::online);
  }

  public boolean isEmpty() {
    return services.isEmpty();
  }
}
