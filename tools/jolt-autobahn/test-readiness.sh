#!/bin/bash
# J6f: the launcher's READY barrier, exercised end to end via the testee's
# J6_DELAY_BIND_MS (a delayed bind still gates on READY) and
# J6_FORCE_BIND_FAIL (a forced bind failure prints FAILED and exits
# nonzero promptly). Edits only; the orchestrator runs this remotely.
#
# Usage: test-readiness.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TESTEE_MAIN="src/dev/jolt_autobahn_testee.clj"
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"

# The bootstrapped jolt; a system jolt lacks the J6-era stdlib modules.
if [ -f build/tools/jolt-env.sh ]; then
  . build/tools/jolt-env.sh
  export PATH="$JOLT_HOME/bin:$PATH"
fi
PORT=9003
LOG="$TOOLS_DIR/testee-readiness.log"
TESTEE_PID=""

cleanup() {
  echo "Readiness test: shutting down..."
  if [ -n "${TESTEE_PID:-}" ]; then
    kill -TERM "$TESTEE_PID" 2>/dev/null || true
    wait "$TESTEE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# wait_for <logfile> <pattern>: 0 when the pattern appears within 60 s;
# 1 when the testee dies first or the wait times out. No tail -f: the
# log is polled, so nothing can hang after the expected line appears.
wait_for() {
  local log="$1" pattern="$2"
  for _ in $(seq 1 300); do
    if ! kill -0 "${TESTEE_PID}" 2>/dev/null; then
      echo "Readiness test: testee exited before the expected line" >&2
      tail -5 "$log" >&2
      return 1
    fi
    if grep -q "$pattern" "$log" 2>/dev/null; then return 0; fi
    sleep 0.2
  done
  echo "Readiness test: timeout waiting for '$pattern'" >&2
  tail -5 "$log" >&2
  return 1
}

# --- Test delay ---
test_delay() {
  echo "Readiness test: J6_DELAY_BIND_MS=5000 must still gate on READY..."
  : > "$LOG"
  J6_ROLE=server J6_PORT=$PORT J6_AGENT=readiness-probe J6_DELAY_BIND_MS=5000 J6_MODE=smoke J6_FRAME_LIMIT=67108864 J6_MESSAGE_LIMIT=67108864 \
    jolt -Srepro -M:jolt "$TESTEE_MAIN" >"$LOG" 2>&1 &
  TESTEE_PID=$!
  wait_for "$LOG" "^READY $PORT"
  echo "Readiness test: delayed bind gated on READY as required."
  kill -TERM "$TESTEE_PID" 2>/dev/null || true
  wait "$TESTEE_PID" 2>/dev/null || true
  TESTEE_PID=""
}

# --- Test fail ---
test_fail() {
  echo "Readiness test: J6_FORCE_BIND_FAIL must print FAILED and exit promptly..."
  : > "$LOG"
  J6_ROLE=server J6_PORT=$PORT J6_AGENT=readiness-probe J6_FORCE_BIND_FAIL=1 J6_MODE=smoke J6_FRAME_LIMIT=67108864 J6_MESSAGE_LIMIT=67108864 \
    jolt -Srepro -M:jolt "$TESTEE_MAIN" >"$LOG" 2>&1 &
  TESTEE_PID=$!
  for _ in $(seq 1 100); do
    if ! kill -0 "$TESTEE_PID" 2>/dev/null; then break; fi
    if grep -q "^FAILED" "$LOG" 2>/dev/null; then break; fi
    sleep 0.2
  done
  grep -q "^FAILED" "$LOG" || { echo "Readiness test: FAILED never printed" >&2; return 1; }
  # The nonzero exit is the assertion: wait must surface it.
  if wait "$TESTEE_PID" 2>/dev/null; then
    echo "Readiness test: forced-fail testee exited zero" >&2
    return 1
  fi
  echo "Readiness test: forced bind failed and exited nonzero promptly."
}

test_delay
test_fail
echo "Readiness test: ALL PASS"
