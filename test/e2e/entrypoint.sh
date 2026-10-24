#!/usr/bin/env bash
# Runs inside the container: starts Xvfb, launches Eclipse with the whole-view probe
# enabled and waits for the probe verdict printed by the plugin.
set -uo pipefail

OUT=${OUT:-/out}
TIMEOUT=${E2E_TIMEOUT:-900}
mkdir -p "$OUT" /workspace
LOG="$OUT/eclipse-console.log"
: > "$LOG"

export DISPLAY=:99
Xvfb :99 -screen 0 1600x1000x24 -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
for _ in $(seq 50); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.2; done

if [ -n "${E2E_VNC:-}" ]; then
  x11vnc -display :99 -forever -shared -nopw -quiet -rfbport 5900 >"$OUT/x11vnc.log" 2>&1 &
fi

# Optional model override; provider keys (ANTHROPIC_API_KEY, OPENAI_API_KEY, ...) come from the environment.
if [ -n "${E2E_MODEL:-}" ]; then
  export OPENCODE_CONFIG_CONTENT="{\"model\":\"${E2E_MODEL}\"}"
fi

# Optional: a copy of an opencode data dir (auth) to use inside the container.
if [ -d /opencode-auth ]; then
  mkdir -p /root/.local/share
  cp -r /opencode-auth /root/.local/share/opencode
fi

git config --global user.email e2e@example.com
git config --global user.name e2e
git config --global --add safe.directory '*'
git -C /workspace init -q 2>/dev/null || true

screenshot() { import -window root "$OUT/$1.png" 2>/dev/null || true; }

/opt/eclipse/eclipse -nosplash -consoleLog -data /workspace/ws \
  -vmargs -Dopencode.wholeViewProbe=true -Dorg.eclipse.swt.browser.DefaultType=webkit \
  >>"$LOG" 2>&1 &
ECLIPSE_PID=$!

verdict=timeout
start=$SECONDS
last_shot=0
while [ $((SECONDS - start)) -lt "$TIMEOUT" ]; do
  if grep -q "\[OpenCodeProbe\] WHOLE VIEW OK" "$LOG"; then verdict=ok; break; fi
  if grep -q "\[OpenCodeProbe\] FAIL" "$LOG"; then verdict=fail; break; fi
  if ! kill -0 "$ECLIPSE_PID" 2>/dev/null; then verdict=crashed; break; fi
  if [ $((SECONDS - last_shot)) -ge 30 ]; then screenshot "progress-$((SECONDS - start))s"; last_shot=$SECONDS; fi
  sleep 2
done

screenshot final
echo "== probe lines =="
grep "\[OpenCodeProbe\]" "$LOG" || true
echo "== verdict: $verdict =="
kill "$ECLIPSE_PID" 2>/dev/null || true
[ "$verdict" = ok ]
