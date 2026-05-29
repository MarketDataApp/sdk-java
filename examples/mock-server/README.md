# mock-server

FastAPI server that backs the scripted-response scenarios in
`../consumer-test`. Endpoints under `/_admin/*` let a consumer app queue
exactly what the next N HTTP responses should look like (status, body,
headers, delay); everything else is the catch-all that pops one step per
incoming request.

## Quick start

```bash
./run.sh
```

Or, from the SDK root:

```bash
make mock-server
```

Either way creates `.venv` (first run only), installs `fastapi` + `uvicorn`,
and starts the server on `http://127.0.0.1:8765`. Leave it running in one
terminal, then run a consumer demo in another (`make demo-config` etc. — see
`examples/consumer-test/README.md`).

## Endpoints

| Path | Method | Purpose |
|---|---|---|
| `/_admin/script` | POST | `{ "steps": [{ "status": 503, "body": "...", "headers": {...}, "delay_ms": 0, "path": "/user/" }] }` — replace the script queue |
| `/_admin/reset` | POST | Drop the script queue, the request log, and the in-flight counters |
| `/_admin/stats` | GET | Snapshot: total requests, peak concurrency, remaining script steps, last 50 log entries |
| `/user/` | GET | Default happy-path body when the script queue is empty (unversioned, mirrors the backend) |
| `/headers/` | GET | Default happy-path body when the script queue is empty (unversioned, mirrors the backend) |
| `/status/` | GET | Default happy-path body when the script queue is empty |
| (anything else) | * | `404 {"s":"no_data"}` when no script step matches — mirrors the backend's `custom_404` |

## Scripted-step semantics

- A step matches the first incoming request whose path equals `step.path`. If
  `path` is omitted, the step matches the next request to any non-admin path.
- `delay_ms` is applied **before** the response is sent. Use it to simulate
  the SDK's 99-second per-request timeout or to make race conditions visible.
- `cf-ray` is added to every response if you don't set it yourself — that's
  what the SDK reads for `requestId` on the response envelope and exception
  context, so populating it makes the demos' logs traceable.
- The four `x-api-ratelimit-*` headers (`limit`, `remaining`, `reset`,
  `consumed`) are added to **every** response — scripted, default, and 404 —
  mirroring the backend's `update_user_quota`. They populate
  `client.getRateLimits()`. To exercise the exhausted-credits / §10.3 preflight
  path, script your own `x-api-ratelimit-remaining: 0` (plus a future `reset`);
  your value wins via `setdefault`.
- Once popped, a step is gone. Re-script if you need the same shape twice.

## Why this is separate from the SDK's tests

The SDK's own JUnit suite covers the same scenarios at the wire level with a
`CapturingClient` stub. This server exists for the **consumer-facing**
scenarios: a human runs a demo, watches the wall-clock backoff between
retries, watches request count climb to 50 under concurrency, and sees the
SDK behave exactly the way the documentation promises a consumer will see it.
