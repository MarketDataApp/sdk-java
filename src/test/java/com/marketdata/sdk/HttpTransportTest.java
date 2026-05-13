package com.marketdata.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marketdata.sdk.exception.NetworkError;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
// These tests cover the SINGLE-ATTEMPT semantics of executeAsync. Retry behavior is exercised
// separately in HttpTransportRetryTest; here we explicitly disable retry so a permit-release
// assertion reflects exactly one HTTP call per executeAsync invocation.
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

  /** Policy with a single attempt — disables retry so each test asserts one HTTP call only. */
  private static final RetryPolicy NO_RETRY =
      new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));

  /**
   * Regression for the synchronous-throw permit leak: if {@code httpClient.sendAsync(...)} throws
   * before returning a future (rare but possible — malformed request, internal NPE, OOM), the
   * {@code whenComplete(release)} chain never forms. Without explicit release in the catch, every
   * such failure burns a permit forever; a long-lived process eventually deadlocks once 50 such
   * failures accumulate.
   *
   * <p>This test runs more requests than {@link HttpTransport#CONCURRENCY_LIMIT} against a stub
   * client whose {@code sendAsync} always throws — if a permit ever leaked, the {@code
   * (limit+1)}-th call would block indefinitely on {@code acquire()} and the test would time out.
   */
  @Test
  void permitReleasedWhenSendAsyncThrowsSynchronously() throws Exception {
    HttpTransport transport =
        new HttpTransport(
            "http://localhost", "v1", "test/0.0", null, new SyncThrowingHttpClient(), NO_RETRY);

    AsyncSemaphore permits = readSemaphore(transport);
    int initial = permits.availablePermits();
    assertThat(initial).isEqualTo(HttpTransport.CONCURRENCY_LIMIT);

    int n = HttpTransport.CONCURRENCY_LIMIT + 5;
    for (int i = 0; i < n; i++) {
      CompletableFuture<Object> f =
          transport.executeAsync(RequestSpec.get("ping").build(), Object.class);

      assertThat(f).isCompletedExceptionally();
      assertThatThrownBy(f::join)
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(NetworkError.class)
          .hasMessageContaining("before dispatch");
    }

    // If even one permit had leaked, this would be < initial; the (limit+1)-th call would
    // also have blocked instead of failing fast.
    assertThat(permits.availablePermits()).isEqualTo(initial);
  }

  /**
   * Errors thrown synchronously by {@link HttpClient#sendAsync} (e.g. {@code OutOfMemoryError})
   * must surface with their original type preserved — wrapping a JVM-level {@link Error} in a
   * {@link com.marketdata.sdk.exception.NetworkError} would mask the real cause and produce a
   * misleading "network failure" for what is actually a runtime crash. Covers the {@code if (t
   * instanceof Error err) throw err;} branch in {@code dispatch}; the {@link
   * java.util.concurrent.CompletableFuture#thenCompose} machinery catches the rethrown Error and
   * exposes it as the future's root cause rather than letting it propagate synchronously.
   */
  @Test
  void errorThrownSynchronouslyIsPreservedAsRootCause() throws Exception {
    HttpTransport transport =
        new HttpTransport(
            "http://localhost", "v1", "test/0.0", null, new ErrorThrowingHttpClient(), NO_RETRY);

    AsyncSemaphore permits = readSemaphore(transport);
    int initial = permits.availablePermits();

    CompletableFuture<Object> f =
        transport.executeAsync(RequestSpec.get("ping").build(), Object.class);

    assertThat(f).isCompletedExceptionally();
    assertThatThrownBy(f::join)
        .isInstanceOf(CompletionException.class)
        .hasRootCauseInstanceOf(OutOfMemoryError.class)
        .hasRootCauseMessage("simulated synchronous Error from sendAsync");

    // Permit released even though the catch took the Error branch — a leak here would
    // accumulate over a long-lived process and eventually deadlock the pool.
    assertThat(permits.availablePermits()).isEqualTo(initial);
  }

  /**
   * Regression for the slow-path cancellation leak (Issue #1, Component A). When the pool is
   * saturated, {@code acquire()} returns a pending waiter that is enqueued. The future the caller
   * actually sees is the downstream {@code thenCompose} result, NOT the waiter. Cancelling the
   * downstream does <em>not</em> propagate to the waiter (standard CompletableFuture semantics), so
   * the waiter is still alive when {@code release()} runs — release() "transfers" the permit by
   * completing the waiter, but the {@code thenCompose} function never executes because its
   * dependent future is already cancelled. Result: the permit is lost forever.
   *
   * <p>This test saturates the pool with {@link HttpTransport#CONCURRENCY_LIMIT} fast-path
   * dispatches whose HTTP futures we control, queues {@code extras} slow-path callers, cancels all
   * the slow-path futures, and then completes the fast-path HTTP futures so {@code release()}
   * fires. Once every dispatch has settled, every permit must be back in the pool.
   */
  @Test
  void permitsAreReleasedWhenSlowPathFuturesAreCancelled() throws Exception {
    ControllableHttpClient client = new ControllableHttpClient();
    HttpTransport transport =
        new HttpTransport("http://localhost", "v1", "test/0.0", null, client, NO_RETRY);

    AsyncSemaphore permits = readSemaphore(transport);
    int initial = permits.availablePermits();
    assertThat(initial).isEqualTo(HttpTransport.CONCURRENCY_LIMIT);

    // Saturate the pool — these go through the fast path (acquire returns an already-completed
    // future), dispatch is invoked, sendAsync is called → ControllableHttpClient returns a
    // pending future we hold the handle to.
    List<CompletableFuture<Object>> fastPath = new ArrayList<>(initial);
    for (int i = 0; i < initial; i++) {
      fastPath.add(transport.executeAsync(RequestSpec.get("ping").build(), Object.class));
    }
    assertThat(permits.availablePermits()).isZero();
    assertThat(permits.queueLength()).isZero();
    assertThat(client.pendingCount()).isEqualTo(initial);

    // Slow path — these enqueue waiters in the semaphore. dispatch is NOT yet called for them.
    int extras = 5;
    List<CompletableFuture<Object>> slowPath = new ArrayList<>(extras);
    for (int i = 0; i < extras; i++) {
      slowPath.add(transport.executeAsync(RequestSpec.get("ping").build(), Object.class));
    }
    assertThat(permits.queueLength()).isEqualTo(extras);

    // Caller cancels every slow-path future. Without the fix, the waiters stay live in the
    // queue — release() will later transfer permits into the cancelled-downstream waiters
    // and the permits disappear.
    for (CompletableFuture<Object> f : slowPath) {
      f.cancel(false);
    }

    // Complete every fast-path HTTP future. Each completion fires whenComplete(release).
    // Failing the future bypasses body decoding (which would NPE on a null response) while
    // still exercising the release path.
    client.failAll(new IOException("simulated end of test"));

    // After every dispatch has settled, the pool must be fully restored.
    assertThat(permits.queueLength()).isZero();
    assertThat(permits.availablePermits())
        .as("every permit should be back in the pool — no leaks from cancelled slow-path futures")
        .isEqualTo(initial);
  }

  // ---------- asRuntime: covers the three branches in the executeSync catch ----------

  @Test
  void asRuntimeReturnsMarketDataExceptionUnchanged() {
    // The `instanceof MarketDataException` branch — the only one reached from the public
    // surface today (every failure from executeAsync is wrapped as an MDE subtype).
    com.marketdata.sdk.exception.BadRequestError mde =
        new com.marketdata.sdk.exception.BadRequestError(
            "bad", com.marketdata.sdk.exception.ErrorContext.empty());

    RuntimeException result = HttpTransport.asRuntime(mde);

    assertThat(result).isSameAs(mde);
  }

  @Test
  void asRuntimeRethrowsNonMdeRuntimeExceptionUnchanged() {
    // Defensive guardrail: if some future code path lets a non-MDE RuntimeException reach
    // .join()'s cause, surface it as-is rather than wrapping it.
    IllegalStateException re = new IllegalStateException("unexpected");

    RuntimeException result = HttpTransport.asRuntime(re);

    assertThat(result).isSameAs(re);
  }

  @Test
  void asRuntimeWrapsNonRuntimeCauseInNetworkError() {
    // Last-resort branch: cause is an Error (or null). Wrap in NetworkError so the public
    // surface still observes the sealed MarketDataException hierarchy.
    OutOfMemoryError error = new OutOfMemoryError("simulated");

    RuntimeException result = HttpTransport.asRuntime(error);

    assertThat(result).isInstanceOf(com.marketdata.sdk.exception.NetworkError.class);
    assertThat(result.getCause()).isSameAs(error);
    assertThat(result.getMessage()).contains("Unexpected failure invoking SDK");
  }

  @Test
  void asRuntimeWrapsNullCauseInNetworkError() {
    // CompletableFuture.join() can in principle deliver a CompletionException whose cause
    // is null (defensive: should never happen in practice but ergonomically harmless).
    RuntimeException result = HttpTransport.asRuntime(null);

    assertThat(result).isInstanceOf(com.marketdata.sdk.exception.NetworkError.class);
    assertThat(result.getCause()).isNull();
  }

  // ---------- unwrap: covers all 4 branches of `t instanceof CE && t.getCause() != null`
  // ----------

  @Test
  void unwrapReturnsNonCompletionExceptionUnchanged() {
    // First branch of `&&` is false → short-circuit, return t as-is. The most common path
    // in production: handle() in CompletableFuture already unwraps CompletionException.
    java.io.IOException io = new java.io.IOException("boom");
    assertThat(HttpTransport.unwrap(io)).isSameAs(io);
  }

  @Test
  void unwrapReturnsCauseOfNestedCompletionException() {
    // Both branches true: CompletionException with a cause. Returns the cause.
    java.io.IOException root = new java.io.IOException("root");
    CompletionException wrapped = new CompletionException(root);

    assertThat(HttpTransport.unwrap(wrapped)).isSameAs(root);
  }

  @Test
  void unwrapReturnsCompletionExceptionWithoutCauseUnchanged() {
    // First branch true, second branch false: CompletionException with `null` cause. The
    // method returns t itself rather than dereferencing the missing cause.
    CompletionException causeless = new CompletionException(null);

    assertThat(HttpTransport.unwrap(causeless)).isSameAs(causeless);
  }

  // ---------- helpers ----------

  private static AsyncSemaphore readSemaphore(HttpTransport t) throws Exception {
    Field f = HttpTransport.class.getDeclaredField("concurrencyPermits");
    f.setAccessible(true);
    return (AsyncSemaphore) f.get(t);
  }

  /**
   * Bare-bones {@link HttpClient} subclass whose {@code sendAsync} throws synchronously. Every
   * other abstract method is stubbed with {@code UnsupportedOperationException} since the test
   * never exercises them.
   */
  private static final class SyncThrowingHttpClient extends HttpClient {
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new IllegalArgumentException("simulated synchronous throw from sendAsync");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Stub {@link HttpClient} whose {@code sendAsync} returns a fresh, never-auto-completing future
   * for each call. The test holds the references and chooses when to complete them — that's the
   * lever the slow-path cancellation regression test pulls to deterministically drive the {@code
   * whenComplete(release)} path.
   */
  private static final class ControllableHttpClient extends HttpClient {
    private final List<CompletableFuture<HttpResponse<?>>> pending = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      CompletableFuture<HttpResponse<T>> f = new CompletableFuture<>();
      pending.add((CompletableFuture<HttpResponse<?>>) (CompletableFuture<?>) f);
      return f;
    }

    int pendingCount() {
      return pending.size();
    }

    void failAll(Throwable t) {
      for (CompletableFuture<HttpResponse<?>> f : pending) {
        f.completeExceptionally(t);
      }
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  /** Same skeleton as {@link SyncThrowingHttpClient} but throws an {@link Error} (OOM-shaped). */
  private static final class ErrorThrowingHttpClient extends HttpClient {
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new OutOfMemoryError("simulated synchronous Error from sendAsync");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }
}
