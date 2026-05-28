package com.marketdata.sdk;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Shared {@link HttpClient} stubs used across transport-layer tests. The JDK only ships abstract
 * implementations of {@code HttpClient}; subclassing it forces stubs for ~12 methods we don't use,
 * so we centralize the noise here.
 */
final class TestHttpClients {

  private TestHttpClients() {}

  /** {@code HttpHeaders} from a flat key→value map. */
  static HttpHeaders headersOf(Map<String, String> entries) {
    Map<String, List<String>> multi = new TreeMap<>();
    entries.forEach((k, v) -> multi.put(k, List.of(v)));
    return HttpHeaders.of(multi, (a, b) -> true);
  }

  /** A canned successful {@link HttpResponse} with the given status, body, and headers. */
  static HttpResponse<byte[]> response(int status, byte[] body, HttpHeaders headers, URI uri) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return status;
      }

      @Override
      public HttpRequest request() {
        return HttpRequest.newBuilder(uri).build();
      }

      @Override
      public Optional<HttpResponse<byte[]>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpHeaders headers() {
        return headers;
      }

      @Override
      public byte[] body() {
        return body;
      }

      @Override
      public Optional<javax.net.ssl.SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        return uri;
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }
    };
  }

  /** Bare-bones {@link HttpClient} with the abstract surface stubbed. */
  abstract static class StubHttpClient extends HttpClient {
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
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bh)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> bh,
        HttpResponse.PushPromiseHandler<T> ph) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  /** Always throws {@link IllegalArgumentException} synchronously from {@code sendAsync}. */
  static final class SyncThrowing extends StubHttpClient {
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      throw new IllegalArgumentException("simulated synchronous throw from sendAsync");
    }
  }

  /** Throws an {@link Error} (e.g. simulated OOM) synchronously. */
  static final class ErrorThrowing extends StubHttpClient {
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      throw new OutOfMemoryError("simulated synchronous Error from sendAsync");
    }
  }

  /** Returns fresh pending futures from {@code sendAsync}; the test controls completion. */
  static final class Controllable extends StubHttpClient {
    private final List<CompletableFuture<HttpResponse<?>>> pending = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> bh) {
      CompletableFuture<HttpResponse<T>> f = new CompletableFuture<>();
      pending.add((CompletableFuture<HttpResponse<?>>) (CompletableFuture<?>) f);
      return f;
    }

    int pendingCount() {
      return pending.size();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void completeAll(HttpResponse<byte[]> response) {
      // Completing a future can trigger downstream callbacks that re-enter sendAsync (e.g. a
      // queued waiter receiving its permit and dispatching), which would CME on `pending`.
      // Snapshot first; any sends that happen as a side effect get scheduled into the next call.
      List<CompletableFuture<HttpResponse<?>>> snapshot = new ArrayList<>(pending);
      for (CompletableFuture f : snapshot) {
        f.complete(response);
      }
    }

    void failAll(Throwable t) {
      List<CompletableFuture<HttpResponse<?>>> snapshot = new ArrayList<>(pending);
      for (CompletableFuture<HttpResponse<?>> f : snapshot) {
        f.completeExceptionally(t);
      }
    }
  }
}
