"""
Scriptable mock server for examples/consumer-test.

Listens on http://127.0.0.1:8765 by default. The consumer apps POST to the
/_admin/* control plane to script the next N responses, then make their real
SDK call against the same host — the catch-all handler pops one response from
the script queue per request and returns exactly what was scripted (status,
body, headers, delay).

When the queue is empty, well-known SDK endpoints (/headers/, /user/,
/status/) get sensible happy-path defaults so apps that don't care about
scripting can still talk to the server.

Run:
  ./run.sh                       # installs deps in .venv and starts uvicorn
  uvicorn server:app --port 8765 # if you manage your own venv
"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any, Optional

from fastapi import FastAPI, Request, Response
from pydantic import BaseModel, Field

app = FastAPI(title="market-data mock server")

# ----------------------------------------------------------------------------
# Scripted-response queue
# ----------------------------------------------------------------------------

class ScriptedStep(BaseModel):
    """One response the server will emit on the next request matching `path`.

    When `path` is omitted, the step matches the next request to ANY path
    (other than /_admin/*). Useful for "the next 3 requests all get 503"
    regardless of which endpoint the SDK hits.
    """

    status: int = 200
    body: str = "{}"
    # Arbitrary response headers. Content-Type defaults to application/json.
    headers: dict[str, str] = Field(default_factory=dict)
    # Sleep before sending the response — used to simulate slow servers, the
    # 99-second per-request timeout, or just visible delay for human eyes.
    delay_ms: int = 0
    # Optional path filter. If set, the step is only popped when the incoming
    # request path equals this value (e.g. "/user/").
    path: Optional[str] = None


# We use a list as a FIFO queue. asyncio.Lock to make pop atomic under
# concurrent requests — necessary for the ConcurrencyApp scenario.
_script_lock = asyncio.Lock()
_script: list[ScriptedStep] = []

# Per-request bookkeeping that consumer apps can read back.
_request_log: list[dict[str, Any]] = []

# Concurrency tracker: counts active requests so we can observe the SDK's
# 50-permit semaphore in action.
_in_flight = 0
_peak_in_flight = 0
_in_flight_lock = asyncio.Lock()

# Default bodies for the well-known SDK endpoints. Match the shapes the
# corresponding deserializers expect.
_DEFAULT_USER = json.dumps(
    {
        "x-ratelimit-requests-remaining": 9999,
        "x-ratelimit-requests-limit": 100000,
        "x-options-data-permissions": "",
    }
)
_DEFAULT_HEADERS = json.dumps(
    {
        "accept": "application/json",
        "user-agent": "marketdata-sdk-java/mock",
        "authorization": "Bearer ***REDACTED***",
        "cf-ray": "mock-ray-id",
    }
)


def _default_ratelimit_headers() -> dict[str, str]:
    # Mirrors the backend's update_user_quota(): every response — including the
    # 404 from custom_404 — carries the four x-api-ratelimit-* headers the SDK's
    # RateLimitHeaders.parse() reads. Parse is all-or-nothing, so always emit all
    # four. Scripts can override any of these (e.g. remaining=0 to drive the
    # §10.3 preflight / exhausted-credits path) — see catch_all's setdefault.
    reset_epoch = int(time.time()) + 24 * 3600  # next daily quota reset
    return {
        "x-api-ratelimit-limit": "100000",
        "x-api-ratelimit-remaining": "99999",
        "x-api-ratelimit-reset": str(reset_epoch),
        "x-api-ratelimit-consumed": "1",
    }


def _default_status_body() -> str:
    now_epoch = int(time.time())
    return json.dumps(
        {
            "s": "ok",
            "service": [
                "/v1/markets/status/",
                "/v1/stocks/quotes/",
                "/v1/options/chain/",
            ],
            "status": ["online", "online", "online"],
            "online": [True, True, True],
            "uptimePct30d": [0.999, 0.998, 0.995],
            "uptimePct90d": [0.999, 0.997, 0.994],
            "updated": [now_epoch, now_epoch, now_epoch],
        }
    )


# ----------------------------------------------------------------------------
# Admin endpoints — used by consumer apps to script behavior
# ----------------------------------------------------------------------------

class ScriptRequest(BaseModel):
    steps: list[ScriptedStep]


@app.post("/_admin/script")
async def set_script(req: ScriptRequest) -> dict[str, Any]:
    """Replace the script queue with `steps`."""
    global _script
    async with _script_lock:
        _script = list(req.steps)
    return {"ok": True, "queued": len(req.steps)}


@app.post("/_admin/reset")
async def reset() -> dict[str, Any]:
    """Drop the script queue, the request log, and the concurrency counters."""
    global _script, _request_log, _in_flight, _peak_in_flight
    async with _script_lock:
        _script = []
    _request_log = []
    async with _in_flight_lock:
        _in_flight = 0
        _peak_in_flight = 0
    return {"ok": True}


@app.get("/_admin/stats")
async def stats() -> dict[str, Any]:
    """Snapshot of what the server has seen since the last /reset."""
    return {
        "requests": len(_request_log),
        "peak_in_flight": _peak_in_flight,
        "remaining_script_steps": len(_script),
        "log": _request_log[-50:],  # last 50 for inspection
    }


# ----------------------------------------------------------------------------
# Catch-all for the SDK's endpoints
# ----------------------------------------------------------------------------

async def _pop_matching_step(path: str) -> Optional[ScriptedStep]:
    """Pop the first script step whose path matches `path` (or is unbound)."""
    async with _script_lock:
        for i, step in enumerate(_script):
            if step.path is None or step.path == path:
                return _script.pop(i)
    return None


@app.api_route("/{full_path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def catch_all(full_path: str, request: Request) -> Response:
    """Default request handler — pops a scripted step if available, else returns
    the well-known happy-path default for the path, else 404."""
    global _in_flight, _peak_in_flight

    # Strip the leading slash so we can compare against the SDK's URL shape
    # (the SDK builds /v1/headers/ as a real absolute path).
    path = "/" + full_path
    method = request.method

    async with _in_flight_lock:
        _in_flight += 1
        if _in_flight > _peak_in_flight:
            _peak_in_flight = _in_flight

    try:
        step = await _pop_matching_step(path)

        if step is not None:
            if step.delay_ms > 0:
                await asyncio.sleep(step.delay_ms / 1000.0)
            response_headers = dict(step.headers)
            # cf-ray is what the SDK uses for requestId — give every response one
            # unless the script explicitly overrode it.
            response_headers.setdefault("cf-ray", f"mock-{int(time.time() * 1000)}")
            response_headers.setdefault("Content-Type", "application/json")
            # Rate-limit headers, unless the script set its own (e.g. remaining=0).
            for _k, _v in _default_ratelimit_headers().items():
                response_headers.setdefault(_k, _v)
            _request_log.append(
                {"path": path, "method": method, "status": step.status, "scripted": True}
            )
            return Response(
                content=step.body,
                status_code=step.status,
                headers=response_headers,
            )

        # No script — return a happy default for the well-known endpoints, or
        # 404 for anything else.
        default_body, default_status = _default_response_for(path)
        _request_log.append(
            {"path": path, "method": method, "status": default_status, "scripted": False}
        )
        return Response(
            content=default_body,
            status_code=default_status,
            headers={
                "Content-Type": "application/json",
                "cf-ray": f"mock-{int(time.time() * 1000)}",
                **_default_ratelimit_headers(),
            },
        )

    finally:
        async with _in_flight_lock:
            _in_flight -= 1


def _default_response_for(path: str) -> tuple[str, int]:
    # /user/ and /headers/ are unversioned in the real backend (no /v1/ prefix),
    # same as /status/. See sdk-java's UtilitiesResource.
    if path in ("/user/", "/user"):
        return _DEFAULT_USER, 200
    if path in ("/headers/", "/headers"):
        return _DEFAULT_HEADERS, 200
    if path in ("/status/", "/status"):
        return _default_status_body(), 200
    # Unknown routes mirror the backend's custom_404: {"s":"no_data"} at 404,
    # NOT an error envelope. The SDK treats 404+no_data as a successful empty
    # response (Response.isNoData()), so this keeps the mock faithful.
    return json.dumps({"s": "no_data"}), 404
