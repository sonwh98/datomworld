Created-GMT: 2026-11-01 04:20:00 GMT
Created-Local: 2026-11-01 11:20:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: fix the Node sharded lane — (A) lost output, (B) a parallel-only dao.stream failure

Role: implementer (Codex). Repo: sonwh98/datomworld (your cwd). You may SSH to
shiva.stigmergy.systems and jupiter.stigmergy.systems read-only-ish (see constraints).

## Background

`src/dev/node_shard.clj` (babashka) runs the Node test lane sharded by subsystem:
one shadow-cljs compile (`:autorun false`), then one
`node target/node-tests.js --test=<comma-list>` per subsystem, up to PAR=12
concurrently via a work-stealing pool. `src/dev/dart_shard.clj` is its Dart twin.
`src/dev/nightly.clj` is the single host driver (lanes as an argument).

## Evidence gathered so far (verify each yourself; do not trust it blindly)

1. **Output is being lost.** On shiva, run `node-shards-20261031-2310`: every
   `*.log` for a completed shard is **0 bytes** even though the shard ran 125 s
   (`dao.stream rc=1 secs=125`) and other shards ran 765-866 s with no visible
   output. Earlier runs did produce logs when output was large (131072 bytes,
   buffer-boundary sized), which points at buffering: the driver passed
   `(io/writer path :append true)` as `:out`/`:err` to `babashka.process/shell`.
   A `BufferedWriter` that the process runner never flushes loses everything
   under one buffer (~8 KiB).
   WORK IN PROGRESS in the tree (uncommitted): those sites were changed to
   `(io/file path)`. **Verify that `:out (io/file p)` / `:err (io/file p)`
   actually capture both streams and survive a non-zero exit**, and that two
   File targets pointing at different paths do not clobber each other. If
   `io/file` truncates when it should append, that is fine as long as each
   shard writes its own files (it does) — but state it.

2. **A parallel-only failure.** Same run, shard `dao.stream` (37 namespaces,
   list in `target-ci/node-shards-20261031-2310/ns-dao.stream.txt`) recorded
   `rc=1 secs=125`. Run ALONE with the same environment it **passes**:
   `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=<that list>` →
   `Ran 528 tests containing 4695 assertions. 0 failures, 0 errors.` exit 0.
   So the red appears only when shards run concurrently. The failure output was
   lost (evidence 1), so the actual failing test is unknown.

3. Known-risky content in `dao.stream`: websocket/datagram tests bind sockets
   (`dao.stream.ws.node-test`, `dao.stream.datagram.*`), some use ephemeral
   ports, and there are filesystem-writing stream tests
   (`dao.stream.journal.file-test`, `dao.stream.memory-log-test`). Several
   shards run in ONE clone (shared cwd, shared `target/`, shared
   `.shadow-cljs`). `dao.stream` also has `ws-project-cross-node-test` and
   RFC6455 tests. Any fixed port, fixed temp path, or engine-level singleton is
   a candidate.

## Your tasks

A. **Make output capture reliable.** Finish/verify the `:out`/`:err` change so
   a shard's stdout+stderr always land in its files regardless of size and exit
   code. If `io/file` is wrong, use the correct babashka API (`:out` can be a
   File/OutputStream/`:string`; `:err` likewise) and prove it with a small
   command that writes to both streams and exits non-zero.

B. **Find and fix the dao.stream parallel red.** Reproduce it: on shiva, run at
   least two shards concurrently that include dao.stream (e.g. dao.stream +
   dao.jing + dao.gui, PAR=3) with the fixed capture, and read the failing test
   and its assertion. Then decide the correct fix:
   - If it is a test-fixture collision (fixed port, fixed temp file), fix the
     fixture to use an ephemeral port / unique temp dir — that is in-scope.
   - If it is a product defect that only shows under concurrency, STOP and
     report it with the repro; do not patch product code from this brief.
   - If the honest answer is "shards must not share a clone for these tests",
     say so and propose the minimal harness change (e.g. serialize the
     socket-binding shards, or give each shard its own clone+cache) — with the
     cost stated.

## Constraints

- **Do not disturb the runs in flight on shiva/jupiter.** Check `pgrep -f
  "node target/node-tests"` before starting anything heavy there; if shards are
  running, use a *separate clone* (`~/ci/datomworld-codex-<something>`) rather
  than the live one, or wait. Never kill another seat's processes.
- Keep edits inside `src/dev/node_shard.clj`, `src/dev/dart_shard.clj`, and
  (only if justified) `src/dev/nightly.clj`. Do NOT commit; the orchestrator
  commits after review. Do not touch `collab/`, `docs/`, or product source.
- Do not invent babashka options. `:err :append` is not a thing (it corrupted
  an exec earlier today and cost hours): verify every option against
  `babashka.process` behavior with a 3-line experiment before using it.
- The tree also contains unrelated uncommitted work by another seat — leave it
  alone.

## Deliverable (reply via stdout; the orchestrator promotes it)

1. Verification result for output capture, with the exact command and observed
   file contents.
2. The dao.stream repro: command, concurrency, and the failing test name with
   its assertion output.
3. Root cause, and either the fix (files + what changed) or a recorded handoff
   if it is product code.
4. Whether any other shard is at risk from the same cause.
5. Anything you could not verify.
