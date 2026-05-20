package com.marketdata.sdk;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.NetworkError;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * Single-shot HTTP dispatch with global concurrency limiting.
 *
 * <p>One {@code HttpDispatcher} per {@link MarketDataClient}. Owns the {@link HttpClient} and the
 * 50-permit {@link AsyncSemaphore} (SDK requirements §12). Each call to {@link #dispatch} acquires
 * a permit, sends the request, and releases the permit exactly once — whether the response
 * succeeds, fails, or the caller cancels the returned future.
 *
 * <p>Failures that originate inside {@code HttpClient.sendAsync} (transport errors, sync-thrown
 * bugs) are mapped to {@link NetworkError} so the upstream retry layer sees a single, typed shape.
 * Status-code interpretation lives in {@link HttpTransport}, not here — this class is below the
 * "what does HTTP 4xx mean" abstraction.
 */
final class HttpDispatcher implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(HttpDispatcher.class.getName());

  private final HttpClient httpClient;
  private final AsyncSemaphore permits;
  private final Clock clock;

  HttpDispatcher(HttpClient httpClient, int concurrencyLimit) {
    this(httpClient, concurrencyLimit, Clock.systemUTC());
  }

  HttpDispatcher(HttpClient httpClient, int concurrencyLimit, Clock clock) {
    this.httpClient = httpClient;
    this.permits = new AsyncSemaphore(concurrencyLimit);
    this.clock = clock;
  }

  /**
   * Send one request. The returned future completes with the raw response on success, or fails with
   * a {@link NetworkError} for transport-level problems. Cancellation of the returned future
   * propagates to the underlying send and, if the dispatch hasn't started yet because we're queued
   * behind the concurrency pool, removes the waiter from the semaphore.
   */
  CompletableFuture<HttpResponse<byte[]>> dispatch(HttpRequest request) {
    CompletableFuture<Void> permit = permits.acquire();
    CompletableFuture<HttpResponse<byte[]>> dispatched =
        permit.thenCompose(unused -> send(request));

    // Cancellation of `dispatched` doesn't propagate to `permit` by default, so a slow-path
    // waiter would stay live in the semaphore queue; release() would later "transfer" the
    // permit by completing the waiter, but thenCompose's function wouldn't run (its dependent
    // is already cancelled), and send — which registers whenComplete(release) — would never
    // fire. Cancelling `permit` here makes AsyncSemaphore.release skip the waiter.
    dispatched.whenComplete(
        (r, t) -> {
          if (t instanceof CancellationException) {
            permit.cancel(false);
          }
        });

    return dispatched;
  }

  private CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request) {
    LOGGER.fine(() -> "GET " + safeUri(request.uri()));
    Instant start = clock.instant();

    CompletableFuture<HttpResponse<byte[]>> sendFuture;
    try {
      sendFuture = httpClient.sendAsync(request, BodyHandlers.ofByteArray());
    } catch (Throwable t) {
      // sendAsync threw synchronously (malformed request, internal NPE, OOM). The future
      // never formed, so the whenComplete below would never fire — release the permit here
      // to prevent a permanent leak that would degrade the pool to deadlock.
      permits.release();
      LOGGER.warning(
          () ->
              "Request to "
                  + safeUri(request.uri())
                  + " failed before dispatch: "
                  + t.getMessage());
      if (t instanceof Error err) {
        throw err;
      }
      return CompletableFuture.failedFuture(
          new NetworkError(
              "Request to " + safeUri(request.uri()) + " failed before dispatch: " + t.getMessage(),
              ErrorContext.forNoResponse(request.uri().toString(), clock.instant()),
              t));
    }

    return sendFuture
        .whenComplete((r, t) -> permits.release())
        .handle(
            (response, error) -> {
              long elapsedMs = Duration.between(start, clock.instant()).toMillis();
              if (error != null) {
                Throwable root = unwrap(error);
                LOGGER.warning(
                    () ->
                        "Request to "
                            + safeUri(request.uri())
                            + " failed after "
                            + elapsedMs
                            + "ms: "
                            + root.getMessage());
                throw new CompletionException(
                    new NetworkError(
                        "Request to " + safeUri(request.uri()) + " failed: " + root.getMessage(),
                        ErrorContext.forNoResponse(request.uri().toString(), clock.instant()),
                        root));
              }
              LOGGER.fine(
                  () ->
                      "Response "
                          + response.statusCode()
                          + " from "
                          + safeUri(request.uri())
                          + " in "
                          + elapsedMs
                          + "ms");
              return response;
            });
  }

  /**
   * Returns a log-safe rendition of {@code uri}: just the path, with a literal {@code "?…"}
   * appended when the URI had a query string. The query is omitted so log lines never persist
   * potentially-sensitive request parameters (PII like {@code account_id}, competitive-signal data
   * like queried symbols, or a hypothetical future {@code ?token=}).
   *
   * <p>Exception context (via {@link ErrorContext}) still carries the full URI: that surface is for
   * consumer code that has context to decide what to do with it; ambient logs are not.
   */
  static String safeUri(URI uri) {
    String path = uri.getPath();
    if (path == null) {
      // Opaque URIs (scheme:opaque, no //authority) — defensive fallback. Won't happen for
      // requests built by this SDK, but log-safety helpers must never throw.
      return uri.toString();
    }
    return uri.getRawQuery() != null ? path + "?…" : path;
  }

  /** Permits not currently held nor queued. Exposed for diagnostics and tests. */
  int availablePermits() {
    return permits.availablePermits();
  }

  /** Number of pending waiters on the semaphore's slow path. */
  int queueLength() {
    return permits.queueLength();
  }

  /**
   * Drains the semaphore's waiter queue and rejects subsequent {@link #dispatch} calls; waiters
   * fail with {@link java.util.concurrent.CancellationException} so the chained future of every
   * pending caller resolves cleanly instead of leaking forever.
   *
   * <p>Does <em>not</em> cancel in-flight HTTP sends: those run inside {@code HttpClient}, which
   * has no {@code close()} until JDK 21 (ADR-002). When the SDK bumps to JDK 21+ this method should
   * also close the {@code HttpClient}.
   *
   * <p>Idempotent.
   */
  @Override
  public void close() {
    permits.close();
  }

  private static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }
}
