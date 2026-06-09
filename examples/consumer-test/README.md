# consumer-test

A collection of small runnable apps that exercise every consumer-facing
behavior of `marketdata-sdk-java`. Each app stands alone — pick the one that
matches the scenario you want to see, run it, read the console output.

Lives under `examples/` rather than as a `src/test` source set on purpose: it
consumes the SDK as an *external* artifact (via `mavenLocal`), so the demos
exercise exactly the shape a published JAR exposes — no accidental package-
private leaks, no access to internal seams.

## One-time setup

```bash
# 1. Publish the SDK to your local Maven cache. Run from the SDK root
#    (two directories up). The Makefile wraps it:
cd ../..
make publish

# 2. (For runLive only) put your token in this directory's .env:
echo "MARKETDATA_TOKEN=your-token-here" > examples/consumer-test/.env
```

## Running

From the SDK root, the easy path is `make` (see `make help` for the full list):

```bash
make demo-quickstart     # idiomatic per-resource tour      — live API, no mock
make demo-live           # full plumbing smoke              — live API, no mock
make demo-config         # config, validation, demo mode    — needs mock server
make demo-exceptions     # every MarketDataException subtype — needs mock server
make demo-retry          # retry, Retry-After, preflight    — needs mock server
make demo-response       # Response<T> features             — needs mock server
make demo-concurrency    # 50-permit semaphore              — needs mock server
make demos-all           # the five mock-server demos back-to-back
```

Or directly from this directory, bypassing the Makefile:

```bash
./gradlew tasks --group "consumer demos"   # list all apps
./gradlew runLive                          # same as `make demo-live`
./gradlew runDemoConfig                    # etc.
```

Apps that say "needs mock server" require the mock running in another
terminal. Easiest:

```bash
make mock-server                           # from the SDK root
# or, equivalently:
cd ../mock-server && ./run.sh
```

Without it the demo fails fast with a clear "server not reachable" message.

## What each app shows

| App | Scenario | What you should see |
|---|---|---|
| **QuickstartApp** | Idiomatic per-resource usage. Designed to **grow** — each new SDK resource adds a section. Start here. | For each wired resource, one short snippet per typical call + console output of the typed data the consumer would actually use. Today: `utilities` (status / user / headers) and `options` (lookup / expirations / strikes / chain incl. sealed filters + `expiration=all` + `rho` / quote / quotes with `countback`). |
| **LiveSmokeApp** | The happy path against the real API | client.toString redacted; sync + async parity on status/user/headers; parallel calls completing in ≈ slowest-single-call wall-time; final rate-limit snapshot populated |
| **DemoAndConfigApp** | Construction-time behavior | demo-mode skip; §16 token redaction (short ≤8 → full; >8 → ***…***ABCD); cascade (explicit wins); IAE on invalid baseUrl / CRLF API key; validateOnStartup 200 vs 401 paths |
| **ExceptionsApp** | Every sealed exception subtype | 401 → AuthenticationError; 400 → BadRequestError; 429 → RateLimitError (+ Retry-After); 500 → ServerError (no retry); 503×4 → ServerError after ≈7s; malformed JSON → ParseError; empty body → ParseError (§29 fix message); connection refused → NetworkError after retries; ADR-002 sealed routing via instanceof |
| **RetryBehaviorApp** | The §9 retry contract | 503→503→200 recovers in ≈3s; Retry-After delta overrides exponential; Retry-After HTTP-date honored; pathological Retry-After (1 day) capped at 10 min — falls back to exponential; §10.3 preflight blocks the 2nd request when snapshot reports remaining=0 (0ms wall-time, 0 server-side requests) |
| **ResponseFeaturesApp** | §13.5 Response<T> surface | isJson / isCsv / isHtml mutually exclusive; 404 + `{"s":"no_data"}` returns successfully with isNoData=true; rawBody() is a defensive copy (consumer mutations don't leak); saveToFile() writes verbatim; toString() omits data + redacts query (§16) |
| **ConcurrencyApp** | §12 / ADR-007 50-permit semaphore | 60 parallel calls; server observes peak in-flight = exactly 50; total wall-time ≈ 2× per-call delay (two batches of 50+10) |

## Adding a section to QuickstartApp

`QuickstartApp` is the only app in this directory designed to be **extended** as
the SDK grows. The other six prove a fixed contract; this one is the running
catalog of "what each resource looks like in consumer code".

When a new resource lands on the SDK:

1. Open `src/main/java/com/marketdata/consumer/QuickstartApp.java`.
2. Uncomment the matching placeholder line in `main(...)` (e.g.
   `// stocksExamples(client);`).
3. Implement `xxxExamples(MarketDataClient client)` following the
   `utilitiesExamples` shape: one `Console.step` + one short SDK call + one
   `Console.ok` per typical use case. Catch `AuthenticationError` separately
   when the endpoint needs a token, so the demo stays runnable in demo mode.
4. Keep each example to **3–5 lines of SDK code** — the goal is "what you'd
   copy-paste into your own app", not exhaustive coverage. Edge cases belong
   in the other demos.

## How the mock server fits

The mock server (FastAPI, `../mock-server/`) is what makes the
non-live demos deterministic. Each demo POSTs a list of scripted responses
to `/_admin/script`, then makes its real SDK call against the same host —
the server's catch-all pops exactly the scripted response. Apps that need to
see specific status codes, headers (Retry-After, x-api-ratelimit-*), or
timing behavior depend on this scripting.

The control plane (`/_admin/*`) is hit with a plain `java.net.http.HttpClient`,
not the SDK — keeping the SDK's surface uncluttered.

> Note: `MockServerControl` forces HTTP/1.1 because uvicorn's HTTP/2 upgrade
> handling drops POST bodies during the negotiation. The SDK itself stays on
> its ADR-004 HTTP/2 default — only the admin client downgrades.

## Caveats

- The local `.env` has a token that's been used during development. If the
  token is no longer valid against api.marketdata.app, `runLive` will show
  `AuthenticationError` on calls that need it (status/ stays public).
- "Demo mode" (no token at all) is hard to see if you have a token in your
  env or `.env` — the cascade picks it up. The demo detects this and prints
  a skip note rather than constructing a misleading client.
