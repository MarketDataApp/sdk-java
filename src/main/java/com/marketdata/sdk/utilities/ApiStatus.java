package com.marketdata.sdk.utilities;

import java.util.List;

/**
 * Response shape for {@code GET /status/} — the per-service health snapshot of the Market Data API.
 *
 * <p>The wire format is parallel arrays; the SDK zips them into a {@code List<ServiceStatus>} here
 * so the abstraction the consumer sees matches the natural "one row per service" model.
 *
 * <p>The status data is updated every 5 minutes server-side; clients that poll more frequently than
 * that are wasting requests on cached results.
 *
 * @param services one entry per service the API exposes. Empty when the server has no services to
 *     report.
 */
public record ApiStatus(List<ServiceStatus> services) {

  public ApiStatus {
    services = List.copyOf(services);
  }
}
