#!/usr/bin/env bash
# Bootstrap a venv if needed, install deps, and start uvicorn on :8765.
# Idempotent — re-running just starts the server with the existing venv.
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -d .venv ]]; then
  python3 -m venv .venv
fi

# shellcheck source=/dev/null
source .venv/bin/activate

pip install --quiet --disable-pip-version-check -r requirements.txt

echo
echo "Mock server starting on http://127.0.0.1:8765"
echo "  GET  /user/         → default user payload"
echo "  GET  /headers/      → default headers payload"
echo "  GET  /status/       → default api-status payload"
echo "  POST /_admin/script → enqueue scripted responses"
echo "  POST /_admin/reset  → clear queue + counters"
echo "  GET  /_admin/stats  → request count + peak concurrency"
echo
echo "Press Ctrl+C to stop."
echo

exec uvicorn server:app --host 127.0.0.1 --port 8765 --log-level warning
