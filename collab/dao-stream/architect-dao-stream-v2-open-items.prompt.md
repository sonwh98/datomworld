Created-GMT: 2026-09-02 18:57:15 GMT
Created-Local: 2026-09-03 01:57:15 Asia/Ho_Chi_Minh

# Task: dao.stream v2 open items — ws-spec amendments and transport-plan decisions

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-03 01:57:15 Asia/Ho_Chi_Minh | Status: active | Rationale: seat rotated fable -> sol at quota (architect.md, 2026-09-03); design-authoring task in the fallback seat

Repository root: /Users/sto/workspace/datomworld (you are running in it; paths below are relative to it).

## Assignment

Seven items are open across two subordinate documents. Neither subordinate plan can answer them; they belong to the contract layer. Produce, for each, a proposed amendment in diff-ready wording: the section it lands in, the exact text, and the rationale. Where an item is genuinely contested, present the options, recommend one, and state what the recommendation trades away.

The deliverable is a PROPOSAL. You are read-only; the orchestrator applies accepted amendments after cross-family review.

## Read first

- docs/design/dao.stream.md — the v2 contract, committed authority; your amendments must not contradict it
- docs/design/dao.stream.ws.md — transport spec to amend (Section A below)
- docs/design/dao.stream.implementation-plan.md — transport plan to decide (Section B below)
- docs/design/yin.repl.implementation-plan.md — the consumer whose phases R3-R5 Section A blocks
- collab/architect-yin-vm-rotation-brief.md — what rounds 1-7 settled

## Section A — ws-spec amendments (docs/design/dao.stream.ws.md; each blocks REPL phases R3-R5)

- A1. Server-side attachment identity. The contract defines `:dao.stream/attachment` only in an `attach!` success map. A server handle is minted from a handed-over socket with no `attach!` call. Define the identity rule for server-minted handles.
- A2. Accept notification. How a composition learns a connection was accepted, and receives its writer handle, without the `:on-connect` callback the spec forbids.
- A3. The wire contract. Handshake presentation, disclaimer form, ended-stream close code, value codec, decode-failure behaviour.

## Section B — transport-plan decisions (docs/design/dao.stream.implementation-plan.md; each blocks Phases 1-3)

- B1. Ring buffer `attach!`: Phase 2 delivers `create!` plus reader/writer/closable; Phase 3 calls `attach!` against a host-kept directory; no phase is assigned to build the ring buffer's `attach!`. Assign it to an existing phase or a new one.
- B2. The transit codec is never named, and whether it is the same codec as the ws value codec (A3) is undetermined. Name it and settle the identity question — one codec or two, with the reason.
- B3. The conformance suite has no concurrency oracle; the contract declines to define one, so the harness author must choose. Recommend one with rationale (what it linearizes against, what it cannot catch).
- B4. The ring buffer's declared exclusion reasons are unrecorded, and the manifest requires a reason for every exclusion. Record them — and remember settled item 5 below when you do.

## Settled — do not re-litigate (each cost multiple review rounds)

1. The VM slice builds `ast-walker` only, not `semantic`. Four evaluators remain to port.
2. The host supplies streams: `create-vm` takes `:make-stream` exactly as it takes `:primitives`. No default.
3. Telemetry is a stub: `emit-snapshot` is a no-op with no stream installed; a non-nil `:telemetry` opt is a construction error.
4. `:stream/take` is removed, not reinterpreted.
5. Reject-mode ring buffers: requested and withdrawn — v1 frees capacity only through the destructive drain (`ringbuffer.cljc:161`); under v2 a full reject-mode buffer is full forever. Do not reintroduce.
6. Totality is the VM's, retention is the composition's: the engine is total over `append!`'s five outcomes including `full`; a ring-buffer composition never sees `full`.
7. `dao.stream.apply` owns the request/response envelope; `dao.stream.rpc.*` requires it.
8. Flow control (4d) is deferred out of the transport slice (pause -> lease -> dao.space on v2 -> not built).
9. USER RULING, 2026-09-03: the REPL plan's `shadow-cljs.edn` exception is APPROVED; not open for this task either.

## Checks this arc's history says actually matter

- Fixes that relocate their defects: after drafting each amendment, grep the doc cluster (dao.stream.md, dao.stream.ws.md, dao.stream.implementation-plan.md, yin.vm.implementation-plan.md, yin.repl.implementation-plan.md) for statements your amendment invalidates, and list them.
- Verify every cited file:line against the working tree.
- Portability: multi-slash keywords like `:a/b/c` read on `:clj` but break the `:cljs`/`:cljd` readers even inside skipped reader-conditional branches. Any codec or wire vocabulary you define spans three hosts.

## Constraints

- Read-only: no file edits. The orchestrator captures your stdout and promotes it to `collab/architect-dao-stream-open-items.gpt-5.6-sol.findings.md`.
- No heartbeat file (read-only run); the orchestrator judges liveness by process health, not output.
- Do not run test suites. Documents-only task; no v2 code exists yet by design.
- A parallel Architect session is signing off `yin.repl.implementation-plan.md`; do not review that plan here.
- Do not stage, commit, or modify anything.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>

Then, per item A1-A3 and B1-B4:
1. Proposed amendment text: section anchor + exact wording to insert or replace.
2. Rationale, grounded in the contract.
3. Invalidated-elsewhere list (statements in the doc cluster this amendment contradicts), or "none found".
4. Any decision you could not make alone, with options.

End with a summary table: item | settles | blocks-until-applied.
