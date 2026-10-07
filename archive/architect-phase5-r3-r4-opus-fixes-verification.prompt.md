Created-GMT: 2026-09-04 04:55:56 GMT
Created-Local: 2026-09-04 11:55:56 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: none (new read-only verification)

# Task: Phase 5 R3/R4 Opus fixes verification

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-04 11:55:56 Asia/Ho_Chi_Minh | Status: active | Rationale: designated architect requested by the user to verify fixes to its prior findings

Perform a read-only architecture verification of the unstaged fixes layered on
the current staged Phase 5 R3/R4 implementation in
`/Users/sto/workspace/datomworld`. Do not edit, stage, or commit anything.

Read first:
- docs/design/datom.world.md
- docs/design/dao.stream.md
- docs/design/dao.stream.ws.md
- docs/design/dao.stream.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- docs/agents/team/architect.md
- collab/architect-phase5-r3-r4-signoff.fable.stdout.log
- collab/stream-phase5-r3-r4-fable-fixes.claude-opus-5.stdout.log
- `git diff` (the unstaged Opus fix delta)
- `git diff --cached` only as needed for surrounding implementation context

Verify whether the unstaged delta correctly closes all four prior MEDIUM
findings:

1. Operator `(disconnect)` must return subsequent ordinary input to local
   evaluation and must not retain remote queued/retrying work.
2. Incomplete input from one served attachment must not leak into another;
   the response must remain correlated and the continuation state reset.
3. Node admission must receive a host clock reading so pending-slot expiry can
   operate.
4. Quit/EOF/headless shutdown must initiate endpoint stop and step it to an
   honest `:stopped` fact or a bounded, explicit timeout.

Also verify the local LOW fixes, especially URI dot-segment/trailing-slash
canonicalization, query-only default paths, pre-upgrade subprotocol refusal,
host-seam contracts, and the stated port-zero limitation. Look specifically for
new races, off-by-one shutdown budgets, process/signal lifecycle mistakes,
cross-platform compile hazards, incorrect WebSocket callback contracts, and
tests that pass without exercising the claimed behavior.

The implementer reports that focused JVM tests, the complete Node test target,
kondo lint, CLJD compile, and Dart analysis pass. Treat those as evidence to
assess; spend the review budget on static analysis and do not rerun the full
suite.

The provenance field in the earlier Fable prompt incorrectly named AGY because
it was copied from an abandoned AGY route. This direct Claude CLI run must use
the metadata below.

Begin the final response exactly with:
Completed-GMT: <actual timestamp>
Completed-Local: <actual timestamp and timezone>
Coding-Agent: claude
Session-ID: <exact id or none>

Then report severity | file:line | invariant/evidence | recommended correction,
explicit disposition of each former MEDIUM finding, passed properties,
unresolved/deferred risks, and finish with exactly one of:
SIGN-OFF: GRANTED
SIGN-OFF: WITHHELD
