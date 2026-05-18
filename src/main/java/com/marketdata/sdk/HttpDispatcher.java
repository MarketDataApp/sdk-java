package com.marketdata.sdk;

import com.marketdata.sdk.exception.ErrorContext;
import com.marketdata.sdk.exception.NetworkError;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
final class HttpDispatcher {

  private final HttpClient httpClient;
  private final AsyncSemaphore permits;

  HttpDispatcher(HttpClient httpClient, int concurrencyLimit) {
    this.httpClient = httpClient;
    this.permits = new AsyncSemaphore(concurrencyLimit);
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
    CompletableFuture<HttpResponse<byte[]>> sendFuture;
    try {
      sendFuture = httpClient.sendAsync(request, BodyHandlers.ofByteArray());
    } catch (Throwable t) {
      // sendAsync threw synchronously (malformed request, internal NPE, OOM). The future
      // never formed, so the whenComplete below would never fire — release the permit here
      // to prevent a permanent leak that would degrade the pool to deadlock.
      permits.release();
      if (t instanceof Error err) {
        throw err;
      }
      return CompletableFuture.failedFuture(
          new NetworkError(
              "Request to " + request.uri() + " failed before dispatch: " + t.getMessage(),
              ErrorContext.forNoResponse(request.uri().toString(), Instant.now()),
              t));
    }

    return sendFuture
        .whenComplete((r, t) -> permits.release())
        .handle(
            (response, error) -> {
              if (error != null) {
                Throwable root = unwrap(error);
                throw new CompletionException(
                    new NetworkError(
                        "Request to " + request.uri() + " failed: " + root.getMessage(),
                        ErrorContext.forNoResponse(request.uri().toString(), Instant.now()),
                        root));
              }
              return response;
            });
  }

  /** Permits not currently held nor queued. Exposed for diagnostics and tests. */
  int availablePermits() {
    return permits.availablePermits();
  }

  /** Number of pending waiters on the semaphore's slow path. */
  int queueLength() {
    return permits.queueLength();
  }

  private static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
  }
}
