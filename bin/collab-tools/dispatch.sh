#!/usr/bin/env bash
# dispatch.sh <engineer|reviewer|architect> <claude|glm|deepseek|muse|claude-cloud> <model> <worktree> <prompt-file> [resume-session-id]
# Run with the harness's background mode (never append '&'). The response lands in <prompt-stem>.stdout.log beside
# the prompt; if that log already exists the next free <stem>-r<n>.stdout.log is used, so nothing is ever truncated.
# Without a resume id the prompt header must carry `Session-ID: <uuid>` (generate-and-record first).
set -eu
die() { echo "dispatch.sh: $*" >&2; exit 2; }
[ $# -ge 5 ] || die "usage: dispatch.sh <engineer|reviewer|architect> <cli> <model> <worktree> <prompt-file> [resume-session-id]"
role=$1 cli=$2 model=$3 wt=$4 prompt=$5 resume=${6:-}
case $role in engineer) mode=(--permission-mode acceptEdits);;
  reviewer|architect) mode=(--permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)");;  # read-only
  *) die "unknown role '$role'";; esac
case $cli in claude) bin=(claude --model "$model");;
  glm|deepseek|muse|claude-cloud) bin=(env "MODEL=$model" "$cli");;
  *) die "unknown cli '$cli'";; esac
wt=$(cd "$wt" && pwd) || die "no such worktree"
prompt=$(cd "$(dirname "$prompt")" && pwd)/$(basename "$prompt"); [ -f "$prompt" ] || die "no such prompt file"
uuid='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
if [ -n "$resume" ]; then sess=(--resume "$resume")
else sid=$(grep -m1 -E "^Session-ID: $uuid" "$prompt" | awk '{print $2}' | tr A-Z a-z || true)
  [ -n "$sid" ] || die "prompt header has no 'Session-ID: <uuid>' and no resume id given (record the id first)"
  sess=(--session-id "$sid"); fi
stem=${prompt%.prompt.md}; [ "$stem" = "$prompt" ] && stem=${prompt%.md}
name=$(basename "$stem" | cut -c1-60)
log="$stem.stdout.log"; n=1; while [ -e "$log" ]; do n=$((n+1)); log="$stem-r$n.stdout.log"; done
echo "dispatch.sh: log -> $log" >&2
cd "$wt"
exec "${bin[@]}" "${sess[@]}" --name "$name" "${mode[@]}" --output-format text \
  -p "Read $prompt and complete it now." < /dev/null > "$log" 2>&1
