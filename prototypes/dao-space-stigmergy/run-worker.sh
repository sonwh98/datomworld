#!/usr/bin/env bash
# Launch a Claude Code worker agent backed by GLM or DeepSeek, collaborating
# over dao.space by stigmergy. See README.md.
#
#   usage: ./run-worker.sh <glm|deepseek> <agent-id>
#
# `glm` and `deepseek` are Claude Code wrapper executables on PATH
# (~/.local/bin) that point an unmodified Claude Code at each provider's
# Anthropic-compatible endpoint and select the model; this script only adds
# the worker prompt and the task-board mission.
set -euo pipefail

provider="${1:?usage: run-worker.sh <glm|deepseek> <agent-id>}"
agent_id="${2:?usage: run-worker.sh <glm|deepseek> <agent-id>}"
here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

command -v "$provider" >/dev/null ||
  { echo "no '$provider' executable on PATH" >&2; exit 2; }

prompt="$(sed "s/{{AGENT_ID}}/$agent_id/g" "$here/worker-prompt.md")"

cd "$repo_root"
exec "$provider" \
  --append-system-prompt "$prompt" \
  --allowedTools "Bash(clj -M -m dao.space.stigmergy:*)" \
  -p "You are agent $agent_id. Work the dao.space task board by the loop in your instructions until no unclaimed tasks remain, then summarize every task you completed and every claim you lost."
