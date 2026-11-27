Created-GMT: 2026-11-26 16:14:25 GMT
Session-ID: pending (new thread)
# Task: prune 4 stale unused-public allowlist entries — the metrics gate is red on main

Role: QA & Verification Engineer (small fix round)

## Failure (CI on main, run 45826642883, job `report`)

`jolt ci/metrics-gate.clj metrics-report.edn ci/metrics-allow.edn` exits 1
with 4 stale `unused-public` entries that clj-kondo no longer reports —
remove them from `ci/metrics-allow.edn` exactly as the gate instructs:

1. ["clj-kondo.exports/jolt-lang/jolt/hooks/jolt/ffi.clj" "defcfn"]
2. ["clj-kondo.exports/jolt-lang/jolt/hooks/jolt/ffi.clj" "with-arena"]
3. ["stdlib/babashka/tasks.clj" "clojure"]
4. ["stdlib/babashka/tasks.clj" "current-task"]

(Entries 1-2 went stale because the FFI stage-1/p1f rounds and upstream
landings removed the call sites those kondo-hook entries tracked; entries
3-4 track babashka-task names changed by the uniform-launcher work.)

## Fix

Edit `ci/metrics-allow.edn` removing exactly those 4 stale entries (keep the
file's format/conventions). Then verify the gate passes: run the same
`jolt ci/metrics-gate.clj` command against a fresh `metrics-report.edn` (the
report job's step "jolt-metrics . metrics-report.edn --format edn
--no-abstractions" with JOLT_VERSION as in the workflow) and confirm 0
problems. Also confirm no OTHER entries in the allowlist are stale (the gate
would have listed them — 4 problems = exactly these 4).

## Rules

Worktree off main (git worktree add /Users/sto/workspace/jolt-ffi-allowfix -b
chore/metrics-allow-prune origin/main — note: origin/main is jolt-lang's
upstream main per the fork's remote layout; check `git remote -v` first and
base off THIS fork's main or the commit the gate ran against, 45826630266's
merge sha on main). One commit; never push (orchestrator pushes via PR or
direct per owner's fork policy — report the commit sha and leave the push
decision in findings). Findings inline in your final response (no separate
file needed for this size): what you removed, the gate rerun output, and the
commit sha.

Return: Completed-* header + the commit sha + the green gate line.
