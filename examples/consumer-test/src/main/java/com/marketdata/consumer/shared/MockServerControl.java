package com.marketdata.consumer.shared;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny client for the FastAPI mock server's /_admin/* endpoints. Each demo
 * that scripts behavior uses this class to:
 *
 * <ul>
 *   <li>verify the mock server is up before the demo runs (fail-fast with a
 *       clear "did you forget to start the server?" message)
 *   <li>queue scripted responses via /_admin/script
 *   <li>read the request counter and peak in-flight via /_admin/stats
 *   <li>reset between demo steps
 * </ul>
 *
 * <p>Uses {@link java.net.http.HttpClient} directly — not the SDK — because
 * the admin plane is intentionally outside the SDK's surface area.
 */
public final class MockServerControl {

  public static final String BASE_URL = "http://127.0.0.1:8765";

  // Force HTTP/1.1 — uvicorn doesn't speak HTTP/2 out of the box. Java's default of HTTP/2 makes
  // the first request attempt an upgrade that uvicorn rejects, and at least in some scenarios
  // the body gets dropped during the fallback. Plain 1.1 sidesteps the whole dance for the admin
  // control plane, which is the only thing this class talks to.
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  /**
   * Throw a helpful error if the mock server isn't running. Call this at the
   * top of every demo that needs it.
   */
  public void requireUp() {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE_URL + "/_admin/stats"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new IllegalStateException(
            "Mock server responded with HTTP " + resp.statusCode() + " — expected 200");
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Mock server is not reachable at "
              + BASE_URL
              + ". Start it in another terminal: cd ../mock-server && ./run.sh",
          e);
    }
  }

  /** Drop the script queue and reset request counters. */
  public void reset() {
    post("/_admin/reset", "{}");
  }

  /** Snapshot of the server's request counter + peak concurrency. */
  public Stats stats() {
    String body = get("/_admin/stats");
    int requests = parseIntField(body, "\"requests\":");
    int peak = parseIntField(body, "\"peak_in_flight\":");
    return new Stats(requests, peak);
  }

  /** Replace the script queue with {@code steps}. */
  public void script(List<Step> steps) {
    StringBuilder json = new StringBuilder("{\"steps\":[");
    for (int i = 0; i < steps.size(); i++) {
      if (i > 0) json.append(',');
      json.append(steps.get(i).toJson());
    }
    json.append("]}");
    post("/_admin/script", json.toString());
  }

  /** Convenience overload for a single step. */
  public void script(Step step) {
    script(List.of(step));
  }

  // ---------- internals ----------

  private String get(String path) {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      return resp.body();
    } catch (Exception e) {
      throw new RuntimeException("GET " + path + " failed", e);
    }
  }

  private void post(String path, String body) {
    try {
      HttpResponse<String> resp =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE_URL + path))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
        throw new RuntimeException(
            "POST "
                + path
                + " returned HTTP "
                + resp.statusCode()
                + " — body sent: "
                + body
                + " — server response: "
                + resp.body());
      }
    } catch (Exception e) {
      throw new RuntimeException("POST " + path + " failed", e);
    }
  }

  /** Cheap JSON extractor — pulls the integer that follows {@code marker} in {@code json}. */
  private static int parseIntField(String json, String marker) {
    int idx = json.indexOf(marker);
    if (idx < 0) return -1;
    int start = idx + marker.length();
    while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
      start++;
    }
    int end = start;
    while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return Integer.parseInt(json.substring(start, end));
  }

  /** Single scripted response. */
  public static final class Step {
    private int status = 200;
    private String body = "{}";
    private Map<String, String> headers = Map.of();
    private int delayMs = 0;
    private String path = null;

    public static Step of(int status, String body) {
      Step s = new Step();
      s.status = status;
      s.body = body;
      return s;
    }

    public Step withHeader(String name, String value) {
      Map<String, String> next = new java.util.LinkedHashMap<>(this.headers);
      next.put(name, value);
      this.headers = next;
      return this;
    }

    public Step delayMs(int ms) {
      this.delayMs = ms;
      return this;
    }

    /** Restrict this step to requests for an exact path (e.g. "/user/"). */
    public Step forPath(String path) {
      this.path = path;
      return this;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder("{");
      sb.append("\"status\":").append(status);
      sb.append(",\"body\":").append(jsonString(body));
      sb.append(",\"delay_ms\":").append(delayMs);
      if (path != null) {
        sb.append(",\"path\":").append(jsonString(path));
      }
      if (!headers.isEmpty()) {
        sb.append(",\"headers\":{");
        boolean first = true;
        for (var e : headers.entrySet()) {
          if (!first) sb.append(',');
          sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
          first = false;
        }
        sb.append('}');
      }
      sb.append('}');
      return sb.toString();
    }

    private static String jsonString(String s) {
      StringBuilder sb = new StringBuilder().append('"');
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> {
            if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
          }
        }
      }
      return sb.append('"').toString();
    }
  }

  /** Convenience builder for "fail with status X N times, then succeed". */
  public static List<Step> failNTimesThenSucceed(int n, int failStatus, String successBody) {
    List<Step> steps = new ArrayList<>(n + 1);
    for (int i = 0; i < n; i++) {
      steps.add(Step.of(failStatus, "{\"s\":\"error\",\"errmsg\":\"transient\"}"));
    }
    steps.add(Step.of(200, successBody));
    return steps;
  }

  public record Stats(int requests, int peakInFlight) {}
}
