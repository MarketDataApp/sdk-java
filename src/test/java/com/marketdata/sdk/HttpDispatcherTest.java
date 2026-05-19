package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.NetworkError;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class HttpDispatcherTest {

  private static final int LIMIT = 4;

  private static HttpRequest req() {
    return HttpRequest.newBuilder(URI.create("http://localhost/ping")).GET().build();
  }

  // ---------- happy path ----------

  @Test
  void dispatchReturnsResponseAndReleasesPermit() {
    HttpClient client =
        new TestHttpClients.StubHttpClient() {
          @SuppressWarnings({"unchecked", "rawtypes"})
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request, HttpResponse.BodyHandler<T> bh) {
            HttpResponse<byte[]> resp =
                TestHttpClients.response(
                    200, "ok".getBytes(), HttpHeaders.of(Map.of(), (a, b) -> true), request.uri());
            return (CompletableFuture) CompletableFuture.completedFuture(resp);
          }
        };
    HttpDispatcher dispatcher = new HttpDispatcher(client, LIMIT);

    HttpResponse<byte[]> resp = dispatcher.dispatch(req()).join();

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(new String(resp.body())).isEqualTo("ok");
    assertThat(dispatcher.availablePermits()).isEqualTo(LIMIT);
    assertThat(dispatcher.queueLength()).isZero();
  }

  // ---------- sync-throw guard ----------

  /**
   * If {@code sendAsync} throws synchronously (malformed request, internal NPE, OOM), the future
   * never forms; without explicit permit release in the catch block, every such failure leaks a
   * permit. With {@code LIMIT + extras} calls against a stub that always throws, a leak would
   * deadlock the pool once {@code LIMIT} requests had accumulated.
   */
  @Test
  void permitReleasedWhenSendAsyncThrowsSynchronously() {
    HttpDispatcher dispatcher = new HttpDispatcher(new TestHttpClients.SyncThrowing(), LIMIT);

    int n = LIMIT + 3;
    for (int i = 0; i < n; i++) {
      CompletableFuture<HttpResponse<byte[]>> f = dispatcher.dispatch(req());
      assertThat(f).isCompletedExceptionally();
      assertThatThrownBy(f::join)
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(NetworkError.class)
          .hasMessageContaining("before dispatch");
    }

    assertThat(dispatcher.availablePermits()).isEqualTo(LIMIT);
  }

  /**
   * Sync-thrown {@link Error} (e.g. simulated OOM) must surface with its type preserved; wrapping
   * it as {@link NetworkError} would mask a JVM-level crash. Permit must still be released.
   */
  @Test
  void errorThrownSynchronouslyIsPreservedAsRootCause() {
    HttpDispatcher dispatcher = new HttpDispatcher(new TestHttpClients.ErrorThrowing(), LIMIT);

    CompletableFuture<HttpResponse<byte[]>> f = dispatcher.dispatch(req());

    assertThat(f).isCompletedExceptionally();
    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasRootCauseInstanceOf(OutOfMemoryError.class);

    assertThat(dispatcher.availablePermits()).isEqualTo(LIMIT);
  }

  // ---------- async failure mapped to NetworkError ----------

  @Test
  void asyncIoExceptionMappedToNetworkError() {
    TestHttpClients.Controllable client = new TestHttpClients.Controllable();
    HttpDispatcher dispatcher = new HttpDispatcher(client, LIMIT);

    CompletableFuture<HttpResponse<byte[]>> f = dispatcher.dispatch(req());
    client.failAll(new IOException("connect refused"));

    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(NetworkError.class);

    NetworkError err = (NetworkError) f.handle((r, e) -> e.getCause()).join();
    assertThat(err.getCause()).isInstanceOf(IOException.class);
    assertThat(err.getMessage()).contains("connect refused");

    assertThat(dispatcher.availablePermits()).isEqualTo(LIMIT);
  }

  // ---------- slow-path cancellation ----------

  /**
   * When the pool is saturated, an extra {@code dispatch} call's permit acquire queues a waiter in
   * the semaphore. The thing {@link HttpDispatcher} adds on top of {@link AsyncSemaphore} here is
   * propagating cancellation: when the caller cancels the dispatch future, the waiter must be
   * marked cancelled so a later {@code release} skips it instead of transferring a permit into the
   * void.
   */
  @Test
  void cancellingQueuedDispatchMarksWaiterAndPermitReturnsToPool() {
    TestHttpClients.Controllable client = new TestHttpClients.Controllable();
    HttpDispatcher dispatcher = new HttpDispatcher(client, /* concurrencyLimit */ 1);

    CompletableFuture<HttpResponse<byte[]>> inflight = dispatcher.dispatch(req());
    assertThat(dispatcher.availablePermits()).isZero();

    CompletableFuture<HttpResponse<byte[]>> queued = dispatcher.dispatch(req());
    assertThat(dispatcher.queueLength()).isOne();

    queued.cancel(false);

    HttpResponse<byte[]> ok =
        TestHttpClients.response(
            200,
            "ok".getBytes(),
            HttpHeaders.of(Map.of(), (a, b) -> true),
            URI.create("http://localhost/ping"));
    client.completeAll(ok);

    assertThat(inflight).isCompleted();
    assertThat(queued).isCancelled();

    // The cancelled waiter was skipped on release; the permit returns to the pool rather
    // than being silently lost.
    assertThat(dispatcher.queueLength()).isZero();
    assertThat(dispatcher.availablePermits()).isOne();
  }

  // ---------- close drains queued waiters ----------

  /**
   * Without {@code close()} drain, a queued waiter sits in the semaphore forever when the owning
   * client is shut down — the {@code thenCompose} chain hanging off it never resolves and the
   * caller's future is leaked. After close, every queued future must fail with {@link
   * CancellationException} so the consumer's await unblocks cleanly.
   */
  @Test
  void closeDrainsQueuedDispatchesWithCancellation() {
    TestHttpClients.Controllable client = new TestHttpClients.Controllable();
    // Concurrency = 1 so the second dispatch is guaranteed to queue.
    HttpDispatcher dispatcher = new HttpDispatcher(client, 1);

    CompletableFuture<HttpResponse<byte[]>> inflight = dispatcher.dispatch(req());
    CompletableFuture<HttpResponse<byte[]>> queued = dispatcher.dispatch(req());

    assertThat(dispatcher.queueLength()).isOne();

    dispatcher.close();

    // The queued waiter was sitting in the semaphore, downstream of a thenCompose. Closing the
    // semaphore completes it with CancellationException; the queued future surfaces it as a
    // CompletionException -> CancellationException, matching how a cancelled future propagates
    // through the rest of the pipeline.
    assertThat(queued).isCompletedExceptionally();
    // The semaphore-level future failed with CancellationException directly, but the dispatcher
    // chains it through thenCompose: that propagation wraps in CompletionException (per
    // CompletionStage contract — only the original cancel propagates "bare").
    assertThatThrownBy(queued::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);

    // The in-flight send remains running (HttpClient.close() is JDK 21+). We let it complete so
    // the test doesn't leave an orphan future.
    HttpResponse<byte[]> ok =
        TestHttpClients.response(
            200,
            "ok".getBytes(),
            HttpHeaders.of(Map.of(), (a, b) -> true),
            URI.create("http://localhost/ping"));
    client.completeAll(ok);
    assertThat(inflight).isCompleted();
  }

  @Test
  void dispatchAfterCloseFailsImmediately() {
    HttpDispatcher dispatcher = new HttpDispatcher(new TestHttpClients.Controllable(), 4);
    dispatcher.close();

    CompletableFuture<HttpResponse<byte[]>> failed = dispatcher.dispatch(req());

    assertThat(failed).isCompletedExceptionally();
    assertThatThrownBy(failed::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  void closeIsIdempotent() {
    HttpDispatcher dispatcher = new HttpDispatcher(new TestHttpClients.Controllable(), 4);
    dispatcher.close();
    dispatcher.close(); // must be safe
  }
}
