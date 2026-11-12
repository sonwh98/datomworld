Created-GMT: 2026-11-02 21:40:00 GMT
Created-Local: 2026-11-03 04:40:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: why does the Docker nightly's node lane run the uncovered residual despite NIGHTLY_SKIP_UNCOVERED=1?

Role: diagnostician (kimi k3.1-pro, read-only)

## The system

`src/dev/node_shard.clj` (babashka, on master at 0a66cad9) runs the Node test lane
sharded by subsystem. Its config:

- `subs`: from `NIGHTLY_SUBSYSTEMS` (env) — on the failing container this was the
  light list: dao.stream, dao.jing, dao.gui, demo, dao.data, dao.postgraphics,
  yang.base, dao.base (verified via `docker inspect`, env present and correct).
- `run-uncovered?`: `(not= "1" (System/getenv "NIGHTLY_SKIP_UNCOVERED"))` — the
  container env has `NIGHTLY_SKIP_UNCOVERED=1` (verified via `docker inspect`), so
  `run-uncovered?` must be false and the uncovered residual shard must be SKIPPED.

## The anomaly (measured, live)

The Docker container `nightly-shiva2` on shiva.stigmergy.systems (image nightly:latest,
started 20:39:54 UTC, commit 0a66cad9): its node lane completed the 8 light shards all
green (`runs.txt`: dao.stream 122s, dao.jing 41s, dao.gui 27s, demo 28s, dao.data 37s,
dao.postgraphics 30s, yang.base 99s, dao.base 27s — each rc=0), and then a node process
began executing **the 176-namespace uncovered residual**:

- Live process (45+ min, ~3.3 GB RSS, ~111% CPU, still writing logs):
  `node target/node-tests.js --test=yin.repl.adapter-test,yin.vm.debruijn.register-test,yin.vm.linker-aut...`
- yin.repl and yin.vm are NOT in the light assignment — they are the heavy subsystems
  assigned to jupiter. Their presence in a shiva shard contradicts both the assignment
  and the skip env.

The container's own `docker inspect` Env (verified) contains BOTH
`NIGHTLY_SUBSYSTEMS=<light list>` AND `NIGHTLY_SKIP_UNCOVERED=1`.

## Your tasks

1. Read `src/dev/node_shard.clj` at 0a66cad9 (git show origin/master:src/dev/node_shard.clj).
   Trace: how `subs` is built, how `run-uncovered?` is computed, and the condition that
   adds the uncovered shard to the run list. Find the path by which the uncovered shard
   could execute despite `NIGHTLY_SKIP_UNCOVERED=1`.
2. Candidates to check (from my own reading, each unproven):
   - The `subs` def caches at namespace load time (a def evaluated when the script
     loads) — but the env is present at process start, so this should be fine.
   - `run-uncovered?` and the `lists` computation are in DIFFERENT lexical scopes
     (`-main`'s let vs a top-level def) — a def evaluated at load time would read the
     env correctly. Both should see it.
   - The `cond->` on `lists`: does the predicate actually gate? A paren or ordering bug
     would add the uncovered shard unconditionally.
   - `NIGHTLY_SKIP_UNCOVERED` vs a different var name in the committed file (a rename
     mismatch between the docstring and the code).
3. State the root cause precisely (file:line), and the minimal fix.
4. If the fix is a one-line env-condition change, state it; do not commit — the
   orchestrator reviews and commits.

## Constraints

Read-only. No commits, no runs on shiva/jupiter (a node lane is in flight on shiva —
its 3.3 GB uncovered process is the anomaly; do not kill or disturb it). Do not modify
anything outside your analysis.

## Deliverable (stdout; orchestrator promotes)

The root cause with file:line, the minimal fix, and whether the same pattern exists in
`src/dev/nightly.clj` (the jvm lane's subset/uncovered handling) and `src/dev/dart_shard.clj`.
