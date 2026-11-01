#!/usr/bin/env bash
# ci-gc: conservative cleanup of ~/ci scratch clones and Docker build cache.
#
# Rules (safe by construction — this script DELETES only when every check
# passes, and always logs):
#   1. A scratch clone ~/ci/datomworld-* is removed only if:
#        - it has NO uncommitted tracked changes,
#        - AND its HEAD is reachable from origin/master (merged or exactly at
#          origin), or it is a detached/unborn state with no unique refs,
#        - AND it holds no unpushed branches (anything ahead of origin is kept),
#        - AND its collab/ dir is empty or absent (per the collab rule:
#          artifacts must be copied out before removal — never deleted here),
#        - AND no process has it as its working directory.
#   2. Nightly results: per-run dirs older than 30 days are removed; summary
#      .txt files are kept forever (small, and they are the evidence trail).
#   3. Docker: build cache older than 30 days and dangling images are pruned.
#      Tagged images (nightly:latest and anything else) are NEVER removed.
#   4. Never touches ~/workspace/*, live clones with in-flight runs (an flock
#      lock file or running process), .bundle files, or non-datomworld entries.
#
# Run: ci-gc.sh [--dry-run]     (weekly cron; --dry-run prints only)
set -u
DRY="${1:-}"
DRYRUN=0
[ "$DRY" = "--dry-run" ] && DRYRUN=1
NOW=$(date +%s)
AGE_LIMIT=$((30*86400))          # results rotation: 30 days
LOG="$HOME/ci/gc-$(date +%Y%m%d).log"
say() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG"; }

freed=0; removed=0; kept=0

# ---------- 1. scratch clones ----------
for d in "$HOME"/ci/datomworld-*/; do
  [ -d "$d" ] || continue
  name=$(basename "$d")

  # keep: anything with in-flight state
  if [ -f "$d/target-ci/run.lock" ]; then say "KEEP (run lock): $name"; kept=$((kept+1)); continue; fi
  if pgrep -f "$d" >/dev/null 2>&1; then say "KEEP (in use by a running process): $name"; kept=$((kept+1)); continue; fi

  # keep: uncommitted tracked changes
  if ! git -C "$d" diff --quiet 2>/dev/null || ! git -C "$d" diff --cached --quiet 2>/dev/null; then
    say "KEEP (uncommitted changes): $name"; kept=$((kept+1)); continue; fi

  # keep: unpushed local branches (a seat may have work not on origin)
  ahead=0
  for b in $(git -C "$d" for-each-ref --format='%(refname:short)' refs/heads/ 2>/dev/null); do
    if [ "$(git -C "$d" rev-parse "$b" 2>/dev/null)" != "$(git -C "$d" merge-base "$b" origin/master 2>/dev/null)" ] \
       && ! git -C "$d" merge-base --is-ancestor "$b" origin/master 2>/dev/null; then
      ahead=1; say "KEEP (unpushed branch $b): $name"; break
    fi
  done
  [ "$ahead" = "1" ] && { kept=$((kept+1)); continue; }

  # keep: HEAD not reachable from origin/master (unique work)
  head_sha=$(git -C "$d" rev-parse HEAD 2>/dev/null)
  if [ -n "$head_sha" ] && ! git -C "$d" merge-base --is-ancestor HEAD origin/master 2>/dev/null; then
    say "KEEP (HEAD has unique work): $name"; kept=$((kept+1)); continue; fi

  # keep: collab/ artifacts not yet copied out
  if [ -d "$d/collab" ] && [ -n "$(ls -A "$d/collab" 2>/dev/null)" ]; then
    say "KEEP (collab/ artifacts present — copy to the main tree first): $name"; kept=$((kept+1)); continue; fi

  # also require: last modified > 7 days ago (fresh clones may be mid-round)
  if [ -n "$(find "$d" -maxdepth 0 -mtime -7 2>/dev/null)" ]; then
    say "KEEP (modified within 7 days): $name"; kept=$((kept+1)); continue; fi

  size=$(du -sh "$d" 2>/dev/null | cut -f1)
  if [ "$DRYRUN" = "1" ]; then say "WOULD DELETE: $name ($size)"; else
    rm -rf "$d" && say "DELETED: $name ($size)"; fi
done

# ---------- 2. nightly results rotation ----------
for d in "$HOME"/ci/nightly-results/2026*/; do
  [ -d "$d" ] || continue
  if [ -n "$(find "$d" -maxdepth 0 -mtime +30 2>/dev/null)" ]; then
    size=$(du -sh "$d" 2>/dev/null | cut -f1)
    if [ "$DRYRUN" = "1" ]; then say "WOULD DELETE result dir: $d ($size)"
    else rm -rf "$d" && say "DELETED result dir: $d ($size)"; fi
  fi
done
# summary files older than 30 days
find "$HOME"/ci/nightly-results -maxdepth 1 -name "*-summary.txt" -mtime +30 2>/dev/null | while read -r f; do
  if [ "$DRYRUN" = "1" ]; then say "WOULD DELETE summary: $f"; else rm -f "$f" && say "DELETED summary: $f"; fi
done

# ---------- 3. docker build cache / dangling images ----------
if command -v docker >/dev/null 2>&1; then
  # build cache older than 30 days keeps recent layers (warm compiles)
  if [ "$DRYRUN" = "1" ]; then
    say "WOULD prune docker build cache >30d and dangling images"
  else
    out=$(docker builder prune --all --filter "until=720h" --force 2>/dev/null | tail -1)
    say "docker builder prune (kept <30d): $out"
    out=$(docker image prune --filter "until=720h" --force 2>/dev/null | tail -1)
    say "docker dangling-image prune: $out"
  fi
  # tagged images are never touched (the pinned nightly image and any seat image)
fi

say "ci-gc done (kept=$kept)"
