Created-GMT: 2026-09-27 06:45:00 GMT
Created-Local: 2026-09-27 13:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 0 fixes)

# Task: Slice 0 — Fix the gate's two P1 findings

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 0 is
uncommitted). The gate returned REQUEST CHANGES with two P1s. Read:
collab/1790463610624-architect-dao-stream-remote-slice0-gate.gpt-6-sol.findings.md

1. P1 (src/cljc/dao/stream/observe.cljc:51): an unrecognized outcome is
   converted to transport-error, then classified as defect/failed —
   contradicting the contract's unrecognized-outcome rule
   (docs/design/dao.stream.md:113). Fix: preserve a well-formed
   unrecognized outcome and classify it as REFUSED; keep malformed-answer
   handling (truly malformed answers) as the separate :defect path it
   already is. Update the doc table and the read/effect classifications
   accordingly (refused on reads -> :refused; on effects -> :failed per
   the established mapping).
2. P1 (src/cljc/yin/vm/engine.cljc:1373): a parked :next/:put waking
   with an unrecognized outcome reaches throw-terminal-resume! while the
   immediate path returns the shaped refusal — the blocked-must-not-
   change law fails. Fix: shape unrecognized wake outcomes as the same
   shaped refusal and let them through terminal-resume-outcome /
   make-woken-run-queue-entries; declared error outcomes stay terminal.

Add or extend tests pinning both behaviors (an unrecognized outcome
classifies as refused in observe/step; a parked entry waking
unrecognized resumes as the shaped refusal).

Constraints: touch only observe.cljc, engine.cljc, and their existing
test files (plus dao.jing.cljc ONLY if a docstring mention of outcome
handling becomes wrong — no behavior change there). ASCII, <= 80 cols,
cljstyle/kondo clean, no commit/stage/checkout/reset/stash, no
diagnostics. Verify all three lanes sequentially/solo, exact counts
(current: JVM 2,214/182,863/0; Node 2,126/49,532/0; Dart 2,088).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
