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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

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
        new HttpTransport("http://localhost", "v1", "test/0.0", null, new SyncThrowingHttpClient());

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
            "http://localhost", "v1", "test/0.0", null, new ErrorThrowingHttpClient());

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
