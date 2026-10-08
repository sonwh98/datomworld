Created-GMT: 2026-09-02 18:57:15 GMT
Created-Local: 2026-09-03 01:57:15 Asia/Ho_Chi_Minh

# Task: yin.repl implementation plan — architect sign-off

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-03 01:57:15 Asia/Ho_Chi_Minh | Status: active | Rationale: seat rotated fable -> sol at quota (architect.md, 2026-09-03); sol has standing from review-v2-plans-r2 and review-yin-repl-plan, but has not seen sign-off rounds 3-7 — the rotation brief carries them

Repository root: /Users/sto/workspace/datomworld (you are running in it; paths below are relative to it).

## Assignment

Sign off — or withhold — `docs/design/yin.repl.implementation-plan.md`. It is the last unsigned plan of the dao.stream v2 arc. Its sibling `yin.vm.implementation-plan.md` was GRANTED at round 7; this plan went through the five-model review (`collab/review-yin-repl-plan.*`) and a rewrite, but never through sign-off.

The bar (standing instruction from the rotation brief): not perfection, but whether a competent engineer could execute the plan's phases without inventing an architectural decision the plan should have made. Say SIGN-OFF: GRANTED or SIGN-OFF: WITHHELD explicitly; if withheld, give the shortest list of changes that would earn it.

## Read first

- collab/architect-yin-vm-rotation-brief.md — what sign-off rounds 1-7 settled and why
- docs/design/dao.stream.md — the v2 contract, committed authority
- docs/design/yin.repl.implementation-plan.md — the plan under sign-off
- docs/design/yin.vm.implementation-plan.md — signed sibling; consistency reference
- docs/design/dao.stream.ws.md and docs/design/dao.stream.implementation-plan.md — dependency surface for phases R3-R5

## Settled — do not re-litigate (each cost multiple review rounds)

1. The VM slice builds `ast-walker` only, not `semantic`. Four evaluators remain to port.
2. The host supplies streams: `create-vm` takes `:make-stream` exactly as it takes `:primitives`. No default.
3. Telemetry is a stub: `emit-snapshot` is a no-op with no stream installed; a non-nil `:telemetry` opt is a construction error.
4. `:stream/take` is removed, not reinterpreted.
5. Reject-mode ring buffers: requested and withdrawn — v1 frees capacity only through the destructive drain (`ringbuffer.cljc:161`); under v2 a full reject-mode buffer is full forever. Do not reintroduce.
6. Totality is the VM's, retention is the composition's: the engine is total over `append!`'s five outcomes including `full`; a ring-buffer composition never sees `full`.
7. `dao.stream.apply` owns the request/response envelope; `dao.stream.rpc.*` requires it.
8. Flow control (4d) is deferred out of the transport slice (pause -> lease -> dao.space on v2 -> not built).
9. USER RULING, 2026-09-03: the REPL plan's single exception to "nothing existing is modified" — adding a `:yin-repl` `:node-script` build to the existing `shadow-cljs.edn` — is APPROVED. Verify the plan records it as an exception with its reason; do not re-litigate it.

## Known-open items — annotate, do not solve

- Three ws-spec amendments (server-side attachment identity; accept notification without `:on-connect`; the wire contract) block phases R3-R5. A parallel Architect session is designing them now. Confirm the plan marks them as blockers; do not design them here.
- Four transport-plan items (ring buffer `attach!` ownership, transit codec identity, concurrency oracle, exclusion reasons) are in that same parallel session.
- The VM plan carries three non-blocking notes from r7 (a fourth fabricated cursor at `vm.cljc:542`; `:make-stream` vs explicit-pair precedence; `ffi/attach` on a pairless VM). Recorded and unresolved by design. Flag only if the REPL plan's correctness depends on their resolution.

## Checks this arc's history says actually matter

- A closure of namespace *names* is not a closure. The VM plan's dependency claim was wrong twice because `:require` names were traced without checking that the required code uses features v2 removed. If this plan claims a dependency closure, verify semantics: grep the claimed-closed set for `drain-one!`, `closed?`, `:woke`, `:position`, `IDaoStreamWaitable`, waiters.
- Fixes that relocate their defects are the recurring failure mode (caught at r2, r6). For every inconsistency you find, also state whether the same claim appears elsewhere in the plan cluster.
- Verify every cited file:line against the working tree before reporting it.
- Portability: multi-slash keywords like `:a/b/c` read on `:clj` but break the `:cljs`/`:cljd` readers even inside skipped reader-conditional branches. This plan spans three hosts; check its examples.

## Constraints

- Read-only: you cannot and must not edit any file. The orchestrator captures your stdout and promotes it to `collab/architect-yin-repl-signoff.gpt-5.6-sol.findings.md`.
- No heartbeat file (read-only run); the orchestrator judges liveness by process health, not output.
- Do not run test suites. This is a documents-only task; no v2 code exists yet by design (`src/cljc/dao/stream/` is all v1).
- Do not stage, commit, or modify anything.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>

Then:
1. SIGN-OFF: GRANTED or SIGN-OFF: WITHHELD.
2. Findings, each as: severity | file:line | invariant/evidence | minimal correction.
3. If withheld: the shortest list that would earn GRANTED.
4. Which requested properties passed review.
