package com.marketdata.examples.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny teaching aid for the cross-cutting examples (concurrency, retry, error handling).
 *
 * <p>Some SDK behaviors are invisible against the live API: you can't <em>see</em> the 50-permit
 * concurrency cap, force a deterministic 503&rarr;503&rarr;200 retry sequence, or reproduce each
 * error code on demand. This class points the SDK at a local mock server (FastAPI, under
 * {@code ../mock-server/}) whose responses you script up front, so those behaviors become
 * observable.
 *
 * <p>It is <strong>not</strong> part of the SDK and a consumer never needs it &mdash; it exists only
 * so these examples are runnable without a paid token or live market conditions. Resource examples
 * ({@code StocksExample}, etc.) talk to the real API instead.
 */
public final class MockServer {

  /** Where {@code ../mock-server/run.sh} listens. Point the client's base URL here. */
  public static final String BASE_URL = "http://127.0.0.1:8765";

  // Force HTTP/1.1: uvicorn's HTTP/2 upgrade can drop POST bodies during negotiation. Only this
  // admin client downgrades; the SDK under test keeps its HTTP/2 default.
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  /**
   * Fail fast with a clear hint if the mock server isn't running. Call this first; without the
   * server these examples can't demonstrate anything.
   */
  public void requireUp() {
    try {
      HttpResponse<String> resp = send("GET", "/_admin/stats", null);
      if (resp.statusCode() != 200) {
        throw new IllegalStateException("mock server replied HTTP " + resp.statusCode());
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Mock server not reachable at "
              + BASE_URL
              + ". Start it in another terminal:  cd ../mock-server && ./run.sh",
          e);
    }
  }

  /** Queue the responses the server will hand back, in order, one per incoming request. */
  public void script(List<Step> steps) {
    StringBuilder json = new StringBuilder("{\"steps\":[");
    for (int i = 0; i < steps.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append(steps.get(i).toJson());
    }
    json.append("]}");
    sendOrThrow("POST", "/_admin/script", json.toString());
  }

  /** Convenience for scripting a single response. */
  public void script(Step step) {
    script(List.of(step));
  }

  /** The same {@code body} repeated {@code count} times &mdash; handy for fan-out demos. */
  public void scriptRepeated(int count, Step step) {
    List<Step> steps = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      steps.add(step);
    }
    script(steps);
  }

  /**
   * The maximum number of requests the server saw in flight at the same moment. Lets the
   * concurrency example <em>observe</em> the SDK's 50-permit cap instead of asserting it.
   */
  public int peakInFlight() {
    String body = sendOrThrow("GET", "/_admin/stats", null).body();
    return intField(body, "\"peak_in_flight\":");
  }

  // ---------- internals ----------

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
        .timeout(Duration.ofSeconds(2));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendOrThrow(String method, String path, String body) {
    try {
      HttpResponse<String> resp = send(method, path, body);
      if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
        throw new RuntimeException(method + " " + path + " → HTTP " + resp.statusCode());
      }
      return resp;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(method + " " + path + " failed", e);
    }
  }

  private static int intField(String json, String marker) {
    int idx = json.indexOf(marker);
    if (idx < 0) {
      return -1;
    }
    int start = idx + marker.length();
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return Integer.parseInt(json.substring(start, end));
  }

  /** One scripted response: an HTTP status, a JSON body, an optional header, an optional delay. */
  public static final class Step {
    private final int status;
    private final String body;
    private int delayMs = 0;
    private final List<String[]> headers = new ArrayList<>();

    private Step(int status, String body) {
      this.status = status;
      this.body = body;
    }

    public static Step of(int status, String body) {
      return new Step(status, body);
    }

    public Step header(String name, String value) {
      headers.add(new String[] {name, value});
      return this;
    }

    /** Hold the response for {@code ms} before replying &mdash; lets fan-out demos overlap. */
    public Step delayMs(int ms) {
      this.delayMs = ms;
      return this;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder("{\"status\":").append(status);
      sb.append(",\"body\":").append(jsonString(body));
      sb.append(",\"delay_ms\":").append(delayMs);
      if (!headers.isEmpty()) {
        sb.append(",\"headers\":{");
        for (int i = 0; i < headers.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          sb.append(jsonString(headers.get(i)[0])).append(':').append(jsonString(headers.get(i)[1]));
        }
        sb.append('}');
      }
      return sb.append('}').toString();
    }

    private static String jsonString(String s) {
      StringBuilder sb = new StringBuilder("\"");
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> {
            if (c < 0x20) {
              sb.append(String.format("\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
          }
        }
      }
      return sb.append('"').toString();
    }
  }

}
