#!/usr/bin/env bash
# Isolated end-to-end test: builds the plugin, installs it into a stock Eclipse inside a
# container with a virtual display, runs the whole-view probe with real model requests.
# Nothing opens on the host desktop.
#
# Env:
#   ECLIPSE_RELEASE   Eclipse simultaneous release to test against (default 2026-03)
#   E2E_MODEL         provider/model, e.g. anthropic/claude-sonnet-4-5
#   ANTHROPIC_API_KEY / OPENAI_API_KEY / ...  provider key(s), passed through if set
#   E2E_AUTH_DIR      optional opencode data dir to copy into the container (credentials)
#   E2E_TIMEOUT       probe timeout in seconds (default 900)
#   E2E_VNC=1         also expose the virtual screen on 127.0.0.1:5900 (host/Tailscale watch)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$(dirname "$here")")
out=${E2E_OUT:-$here/out}
image=opencode-eclipse-e2e
mkdir -p "$out"

docker build -f "$here/Dockerfile" \
  --build-arg "ECLIPSE_RELEASE=${ECLIPSE_RELEASE:-2026-03}" -t "$image" "$root"

args=(--rm -v "$out:/out" -e "E2E_TIMEOUT=${E2E_TIMEOUT:-900}")
for v in E2E_MODEL E2E_VNC ANTHROPIC_API_KEY OPENAI_API_KEY GEMINI_API_KEY OPENROUTER_API_KEY; do
  [ -n "${!v:-}" ] && args+=(-e "$v")
done
[ -n "${E2E_AUTH_DIR:-}" ] && args+=(-v "$E2E_AUTH_DIR:/opencode-auth:ro")
[ -n "${E2E_VNC:-}" ] && args+=(-p 0.0.0.0:5900:5900)

docker run "${args[@]}" "$image"
echo "Logs and screenshots: $out"
