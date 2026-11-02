#!/bin/bash
# J6f Autobahn remote testee: the server-role route on a Linux server.
# Runs the testee with stdout to a log file, waits for its READY line or
# death, then waits on the process -- the harness's SIGTERM (or the
# suite's completion) is the defined stop, and the exit status propagates.
#
# Usage: remote-testee.sh [port] [logfile]   (default port 19001;
#                                             run from the repo root)

set -euo pipefail

PORT="${1:-19001}"
LOG="${2:-/tmp/j6f-testee.log}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# The bootstrapped jolt (its stdlib carries jolt.socket.native and
# jolt.io-poller); a system jolt lacks the J6-era stdlib modules.
if [ -f build/tools/jolt-env.sh ]; then
  . build/tools/jolt-env.sh
  export PATH="$JOLT_HOME/bin:$PATH"
fi

: > "$LOG"
J6_ROLE=server J6_PORT="$PORT" J6_AGENT="${J6_AGENT:-autobahn}" J6_MODE=smoke \
J6_HOST="${J6_HOST:-0.0.0.0}" J6_FRAME_LIMIT=67108864 J6_MESSAGE_LIMIT=67108864 \
  jolt -Srepro -M:jolt src/dev/jolt_autobahn_testee.clj >"$LOG" 2>&1 &
TESTEE_PID=$!

# The READY barrier: the testee prints "READY <port>" once its bind
# succeeded (the kernel is accepting by then); FAILED or early death
# fails fast.
READY=0
for _ in $(seq 1 300); do
  if ! kill -0 "$TESTEE_PID" 2>/dev/null; then
    echo "remote-testee: testee exited before READY; log tail:" >&2
    tail -5 "$LOG" >&2
    exit 1
  fi
  if grep -q "^READY $PORT" "$LOG" 2>/dev/null; then READY=1; break; fi
  if grep -q "^FAILED" "$LOG" 2>/dev/null; then
    echo "remote-testee: testee reported FAILED; log tail:" >&2
    tail -5 "$LOG" >&2
    kill "$TESTEE_PID" 2>/dev/null || true
    exit 1
  fi
  sleep 0.2
done
[ "$READY" = 1 ] || { echo "remote-testee: READY timeout (60 s)" >&2; kill "$TESTEE_PID" 2>/dev/null || true; exit 1; }
echo "remote-testee: testee ready on $PORT (pid $TESTEE_PID, log $LOG); waiting on it."
wait "$TESTEE_PID"
