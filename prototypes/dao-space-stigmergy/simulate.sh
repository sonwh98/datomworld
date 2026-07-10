#!/usr/bin/env bash
# Prove the whole stigmergy loop over the wire WITHOUT any LLM or API key.
# Scene 1: two scripted workers race a claim; the read-side rule picks one
#          winner; the winner's result settles the task.
# Scene 2: claim leases (gap 10) — a "crashed" winner's claim expires, the
#          task returns to the pool, a second worker re-claims and delivers.
# This is exactly what the LLM workers do — the model only replaces the
# scripted "decide" step.
#   usage: ./simulate.sh
set -euo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

cli() { clj -M -m dao.space.stigmergy "$@"; }

start_coordinator() { # port [lease-ms]
  clj -M -m dao.space.stigmergy serve "$@" &
  coord_pid=$!
  for _ in $(seq 1 30); do
    if cli vocabulary >/dev/null 2>&1; then break; fi
    sleep 1
  done
}

stop_coordinator() { kill "$coord_pid" 2>/dev/null || true; }
trap stop_coordinator EXIT

echo "==================== scene 1: race and settle ===================="
port=$(( 20000 + RANDOM % 20000 ))
export DAO_SPACE_URL="ws://127.0.0.1:$port"
start_coordinator "$port"

task_id="task-$(date +%s)"

echo "== poster deposits a task (names no recipient)"
cli deposit poster "[{:task/id \"$task_id\" :task/title \"write a haiku about tuple spaces\" :task/posted true}]"

echo "== the board interprets the raw facts: unclaimed"
cli board

echo "== worker-glm claims; worker-deepseek races a moment later"
cli deposit worker-glm "[{:claim/task \"$task_id\" :claim/by \"worker-glm\"}]"
cli deposit worker-deepseek "[{:claim/task \"$task_id\" :claim/by \"worker-deepseek\"}]"

echo "== both claims are durable facts; the board's rule picks one winner"
cli board

echo "== the winner deposits the result; the task settles permanently"
cli deposit worker-glm "[{:result/task \"$task_id\" :result/by \"worker-glm\" :result/output \"facts settle in the space / no one calls, yet all respond / the medium recalls\"}]"
cli board

echo "== provenance: who deposited anything, ever"
cli query '[:find ?a :where [?e :dao/agent ?a]]'

stop_coordinator

echo
echo "============ scene 2: a lease heals a crashed winner ============="
port=$(( 20000 + RANDOM % 20000 ))
export DAO_SPACE_URL="ws://127.0.0.1:$port"
start_coordinator "$port" 6000   # 6s lease

task_id="task-lease-$(date +%s)"

echo "== poster deposits; worker-crash claims (6s lease), then dies"
cli deposit poster "[{:task/id \"$task_id\" :task/title \"summarize the lease rule\" :task/posted true}]"
cli deposit worker-crash "[{:claim/task \"$task_id\" :claim/by \"worker-crash\"}]"
cli board

echo "== waiting out the lease..."
sleep 7

echo "== the claim expired with no result: the task is unclaimed again"
echo "   (the dead claim remains in the log — only the interpretation changed)"
cli board

echo "== worker-rescue re-claims and delivers"
cli deposit worker-rescue "[{:claim/task \"$task_id\" :claim/by \"worker-rescue\"}]"
cli deposit worker-rescue "[{:result/task \"$task_id\" :result/by \"worker-rescue\" :result/output \"a claim is a lease: deliver before it expires or the task returns to the pool\"}]"
cli board

echo "== done (coordinator stopping)"
