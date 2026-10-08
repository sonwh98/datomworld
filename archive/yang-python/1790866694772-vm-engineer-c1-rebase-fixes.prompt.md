Created-GMT: 2026-10-01 08:15:00 GMT
Created-Local: 2026-10-01 15:15:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (C1 rebase fixes)

# Task: yang.python C1 — Diagnose and Fix the Rebased-Tree Lane Failures

Role: Compiler & AST Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-py-c1 (branch
yang-python-phase-c1, just rebased onto master @ df7cf1f4; HEAD
fb1c02da = C1 9583809c + the gate-r4 signed-zero fix).

The C1 gate r4 verified the PRE-rebase state (Verdict READY, Sign-off
GRANTED; findings in
collab/1790856811617-reviewer-python-phase-c1-gate-r2.gpt-6.1-sol.findings-r3.md
and the r4 round of the same basename). The orchestrator's tri-host
lanes on the REBASED tree then found:
- JVM: 2,839 tests / 225,791 assertions, 2 FAILURES, 0 errors.
- CLJD: "Error while executing task: test:cljd" (the error detail was
  lost to an output filter; re-run with full logs).
- Node: 2,656 tests / 91,265 assertions, 0 failures, 0 errors.

The rebase brought master's intervening work into the tree: the DHT
epic (dao.space.dht, yin.repl link/index/query changes), yin.vm
task-heap cells, the pure data host module, effects as an unforgeable
host type, host-typed closures (D6/D7), mark-sweep heap reclamation,
the canonical-CBOR addressing changes, and deps.edn's
build/antlr/python3/classes path (run clj -M:antlr-gen first if the
suite dies on ClassNotFoundException: yang.python.antlr.gen.*).

Work items:
1. Identify both JVM failures precisely (full-output rerun; check
   whether either is the known timing-bound slice_test WebSocket flake
   the-deposit-medium-outlives-the-socket, which failed once before on
   an unrelated tree). Root-cause each against the rebase delta.
2. Fix them. If a failure is the pre-existing flake reproducing
   independently of this tree, say so with evidence and leave it.
3. Re-run the CLJD lane with full logs; fix whatever the task error is
   (candidate: stale generated-parser state, a cljd compile break from
   the rebase, or the known antlr-gen requirement).
4. Re-run all three lanes sequentially and solo until green; report
   exact counts.
5. If a fix would need files beyond yang.python sources/tests, the
   linker/dht namespaces, or their tests, STOP and report BLOCKED with
   evidence.

Constraints: pure ASCII, <= 80 columns on added/edited lines; cljstyle
and kondo clean; no commit/stage (the orchestrator commits after the
confirmation round); no checkout/reset/stash; no leftover diagnostics;
JVM tooling under mise (mise exec -- <cmd>); CLJD lane solo.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
