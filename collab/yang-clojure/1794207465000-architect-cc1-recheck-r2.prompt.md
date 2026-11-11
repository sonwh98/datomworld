Created-GMT: 2026-11-09 07:0x GMT (real header below)
Coding-Agent: codex
Session-ID: 41428617-2047-4d4d-95e8-db0d606647c1

# Task: cc1 re-check r2 (quota retry) — focus on the metadata-loss concern from the cut-off r1

Role: Lead System Architect (read-only; your r1 was cut by a quota limit)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-09 | Status: active

## Context

Your r1 (collab/yang-clojure/1794163259000-architect-cc1-recheck-signed-ruling.gpt-6-astra.stdout.log)
ran out of quota mid-verdict. Before cut-off you wrote, in substance: origins flow
from the recorded correspondence through the occurrence walk, but a metadata-loss
case causes the record — and therefore the per-occurrence table — to report an
incorrect origin, and you were beginning to enumerate "new defects".

## What has changed since

- The signed ruling is of record on master: 368297c3 (U1-U3).
- cc1 is rebased on current master: origin/callsite-contract-clojure @ fa70516f.
- cc1's changed-set is GREEN on both lanes, scoped to its 3 touched test namespaces:
  node 27 tests/242 assertions/0 failures; JVM 27/242/0 (measured by the orchestrator).
- master also gained f4df9893 (php prelude balance) — unrelated to cc1's surface.

## Your job (finish the verdict)

1. Re-establish the metadata-loss concern precisely: which code path, which input
   shape, which invariant (U1 read-from-history) it would violate. Quote file:line.
2. Decide whether it is (a) a REAL defect in the current fa70516f tree — if so,
   enumerate each with file:line and severity, reproducible input, and the minimal
   correction; or (b) already handled by round-5's construction-threaded origins and
   your r1 was tracing pre-fix code.
3. Additionally: is the corpus signature change (master: StackOverflowError; cc1:
   deadline waiting for response 14) compatible with U1's implementation, or does it
   indicate the occurrence change altered evaluation cost/structure in a way that
   implicates cc1? (The php corpus root cause is now known: prelude chain depth,
   fixed on master by f4df9893 — so account for that.)
4. Deliver: merge readiness verdict, explicit. This terminates the cc1 review.

Constraints: read-only; no test runs. Begin the final response exactly with
Completed-GMT / Completed-Local.
