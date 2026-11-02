#!/bin/bash
# J6f Autobahn launcher: the thin wrapper over harness.py run.
# harness.py owns the whole lifecycle -- it spawns the testee (the command
# after --), waits for the listener (its readiness barrier, with the
# child-exited guard), starts the role-correct Dockerized wstest with the
# generated spec.json, evaluates, and refuses stale report dirs. This
# script only picks the arguments and prints where the evidence went.
#
# Usage: launch.sh <role> <agent>
#   role: 'client' (local testee dials the suite's fuzzingserver) or
#         'server' (local testee listens; the suite dials in via --network host)
#   agent: the harness agent name (alphanumeric, underscores, hyphens)
#   case_indexes_csv: RESERVED, currently unused -- harness.py run always
#     regenerates the full filtered case index and sets J6_CASE_INDEXES for
#     the testee itself, so a caller-supplied subset cannot reach the child
#     through this wrapper. Filtered re-runs belong in harness.py.
#
# Requirements: docker (role-correct wstest per pin.json), python3, and
# the test build (jolt on PATH, the native parser library built).

set -euo pipefail

ROLE="${1:-}"
AGENT="${2:-}"
CASE_INDEXES="${3:-}"

if [ "$ROLE" != "client" ] && [ "$ROLE" != "server" ]; then
  echo "Usage: $0 <role: client|server> <agent>" >&2
  exit 1
fi
[ -n "$AGENT" ] || { echo "Usage: $0 <role: client|server> <agent>" >&2; exit 1; }

TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$TOOLS_DIR/../.." && pwd)"
MODE="${J6_MODE:-smoke}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$TOOLS_DIR/results/$AGENT/$MODE-$ROLE-$STAMP"
PORT="${J6_PORT:-19001}"

cd "$REPO_ROOT"

# The testee and the harness children need the bootstrapped jolt (its
# stdlib carries jolt.socket.native and jolt.io-poller) and its bin on
# PATH; a Homebrew/system jolt lacks the J6-era stdlib modules.
if [ -f build/tools/jolt-env.sh ]; then
  . build/tools/jolt-env.sh
  export PATH="$JOLT_HOME/bin:$PATH"
fi

TESTEE_CMD=(jolt -Srepro -M:jolt src/dev/jolt_autobahn_testee.clj)
echo "Launcher: harness run role=$ROLE mode=$MODE out=$OUT"
python3 "$TOOLS_DIR/harness.py" run \
  --role "$ROLE" \
  --agent "$AGENT" \
  --mode "$MODE" \
  --out "$OUT" \
  --port "$PORT" \
  --timeout "${J6_TIMEOUT:-1800}" \
  -- "${TESTEE_CMD[@]}"

echo "Launcher: results in $OUT (report/summary.json has the per-case verdicts)."
