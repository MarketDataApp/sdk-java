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
}
