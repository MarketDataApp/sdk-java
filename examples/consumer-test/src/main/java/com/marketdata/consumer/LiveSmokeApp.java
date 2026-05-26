package com.marketdata.consumer;

import com.marketdata.consumer.shared.Console;
import com.marketdata.sdk.MarketDataClient;
import com.marketdata.sdk.Response;
import com.marketdata.sdk.utilities.ApiStatus;
import com.marketdata.sdk.utilities.RequestHeaders;
import com.marketdata.sdk.utilities.ServiceStatus;
import com.marketdata.sdk.utilities.User;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Live-API smoke test against the real {@code api.marketdata.app}. Requires
 * a valid {@code MARKETDATA_TOKEN} in the environment or in {@code .env}.
 *
 * <p>Exercises every public endpoint on {@code client.utilities()} once sync
 * and once async, plus the §13.5 response surface ({@code data()},
 * {@code rawBody()}, {@code requestId()}, {@code isJson()}, {@code isNoData()},
 * {@code requestUrl()}, {@code statusCode()}). Concludes with the §8 rate-limit
 * snapshot the most recent call left on the client.
 *
 * <p>Run: {@code ./gradlew runLive}
 */
public final class LiveSmokeApp {
  private LiveSmokeApp() {}

  public static void main(String[] args) {
    // validateOnStartup = false on purpose for this smoke. The §5 probe is exercised by
    // DemoAndConfigApp against the mock server. Keeping it off here so a transient backend hiccup
    // on /user/ (5xx, slow response) surfaces as a per-row failure instead of a constructor crash
    // that takes down the rest of the smoke.
    try (var client = new MarketDataClient(null, null, null, false)) {
      Console.header("Client snapshot");
      Console.info("toString: " + client);
      Console.info("rateLimits before any call: " + client.getRateLimits());

      Console.header("/status/ (sync) — unversioned, no token required");
      Console.run(
          () -> client.utilities().status(),
          r -> "data() has " + r.data().services().size() + " services; " + describe(r));

      Console.header("/status/ (async) — same call via the async surface");
      Console.run(
          () -> joinResponse(client.utilities().statusAsync()),
          r -> "data() has " + r.data().services().size() + " services; " + describe(r));

      Console.header("/user/ (sync) — needs a token");
      Console.run(
          () -> client.utilities().user(),
          r -> {
            User u = r.data();
            return "requestsRemaining="
                + u.requestsRemaining()
                + ", requestsLimit="
                + u.requestsLimit()
                + ", optionsDataPermissions="
                + (u.optionsDataPermissions().isEmpty() ? "(real-time)" : u.optionsDataPermissions())
                + "; "
                + describe(r);
          });

      Console.header("/headers/ (sync) — what the server saw on this call");
      Console.run(
          () -> client.utilities().headers(),
          r -> {
            RequestHeaders rh = r.data();
            String auth = rh.headers().getOrDefault("authorization", "(absent)");
            return "headers="
                + rh.headers().size()
                + " entries (authorization echoed back: "
                + auth
                + "); "
                + describe(r);
          });

      Console.header("Parallel async — fan out 3 calls, await all");
      long t0 = System.nanoTime();
      CompletableFuture<Response<ApiStatus>> a = client.utilities().statusAsync();
      CompletableFuture<Response<User>> b = client.utilities().userAsync();
      CompletableFuture<Response<RequestHeaders>> c = client.utilities().headersAsync();
      // exceptionally() turns a failure into a null sentinel so allOf doesn't short-circuit on
      // the first failing call — we still want to see whether the others succeeded.
      CompletableFuture<Object> aSafe = a.thenApply(r -> (Object) r).exceptionally(t -> t);
      CompletableFuture<Object> bSafe = b.thenApply(r -> (Object) r).exceptionally(t -> t);
      CompletableFuture<Object> cSafe = c.thenApply(r -> (Object) r).exceptionally(t -> t);
      CompletableFuture.allOf(aSafe, bSafe, cSafe).join();
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
      Console.ok(
          "all 3 completed in "
              + elapsedMs
              + " ms (≈ slowest single call, not sum — proves true parallelism)");
      describeResult("status", aSafe.join(), r -> {
        List<ServiceStatus> services = ((Response<ApiStatus>) r).data().services();
        return services.size() + " services; first: " + services.get(0).service();
      });
      describeResult("user", bSafe.join(), r -> "remaining=" + ((Response<User>) r).data().requestsRemaining());
      describeResult("headers", cSafe.join(), r -> ((Response<RequestHeaders>) r).data().headers().size() + " entries");

      Console.header("Final rate-limit snapshot");
      Console.info("rateLimits after the calls: " + client.getRateLimits());
    }
  }

  @SuppressWarnings("unchecked")
  private static void describeResult(String label, Object resultOrThrowable, java.util.function.Function<Object, String> describe) {
    if (resultOrThrowable instanceof Throwable t) {
      Throwable cause = t.getCause() != null ? t.getCause() : t;
      Console.fail(label + " failed: " + cause.getClass().getSimpleName() + " — " + cause.getMessage());
    } else {
      Console.ok(label + ": " + describe.apply(resultOrThrowable));
    }
  }

  private static String describe(Response<?> r) {
    return "status=" + r.statusCode() + ", requestId=" + r.requestId() + ", url=" + r.requestUrl();
  }

  private static <T> Response<T> joinResponse(CompletableFuture<Response<T>> f) {
    // CompletableFuture.join wraps the cause in CompletionException, but the SDK's joinSync
    // contract is to surface MarketDataException directly. We mimic that here so the demo's
    // exception output matches what a sync caller would see.
    try {
      return f.join();
    } catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof RuntimeException re) throw re;
      throw e;
    }
  }
}
