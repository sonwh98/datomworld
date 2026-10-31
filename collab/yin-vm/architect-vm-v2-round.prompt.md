Created-GMT: 2026-09-02 22:39:39 GMT
Created-Local: 2026-09-03 05:39:39 Asia/Ho_Chi_Minh

# Task: VM-plan amendments, streams-note deferral, and transport-plan sign-off

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 05:39:39 Asia/Ho_Chi_Minh | Status: active | Rationale: seat rotated back to primary at quota reset (architect.md rotation history); you held it through yin.vm sign-off rounds 1-7

Repository root: /Users/sto/workspace/datomworld (you are running in it; paths
below are relative to it). You PROPOSE wording; the orchestrator applies it.
This is a read-only run — you cannot edit files, and the orchestrator captures
your stdout and promotes it to
`collab/architect-vm-v2-round.claude-fable-5-1.findings.md`.

## Read first

- docs/design/yin.vm.implementation-plan.md — your signed plan; jobs (a)-(c) target it
- collab/architect-yin-vm-signoff-r7.claude-fable-5-1.stdout.log — your own r7 notes (job a's source)
- collab/architect-yin-repl-fixes.gpt-5.6-sol.findings.md — Follow-up section carries job (b)'s exact text
- docs/design/dao.stream.md, docs/design/dao.stream.ws.md, docs/design/dao.stream.implementation-plan.md — job (d)
- collab/architect-dao-stream-open-items.gpt-5.6-sol.findings.md and its -r2, plus collab/review-dao-stream-open-items.gemini-3.1-pro-high.findings.md and its -r2 — the consensus trail behind job (d)'s amendments

## Jobs

**(a) Your three r7 non-blocking notes — propose the closures.**
1. The fourth fabricated cursor (`vm.cljc:542` writes `call-in-cursor-key`,
   nothing in `src` reads it): r7 said "either drop it explicitly or count it
   as the fourth — say which." Your own r7 GRANTED text already treats the
   mints as three, not four ("`call-in-cursor-key` is dropped"). If the plan
   states that explicitly, verify; if not, propose the sentence.
2. `:make-stream` vs explicit `:call-in`/`:call-out` precedence: at r7 you
   preferred v1's explicit-handle-first precedence. The REPL fix round
   already adopted explicit-pair-first wording in its own plan; check the VM
   plan's FFI-pair section states the same order and propose alignment if
   not.
3. `ffi/attach` on a pairless VM must error exactly as construction does.
   The plan's FFI-pair section already says this — verify the wording is
   explicit enough to execute and propose tightening only if it is not.

**(b) V1 RPC transition contract — propose the insertion.** The signed plan's
V1 wording is incomplete on the RPC transition algebra; the REPL fix round
drafted the exact contract V1 must adopt (its Follow-up section, quoted in
full — adopt or amend it): `:next-id` allocator rules, `request!`/`poll!`
totality over `append!`/`next`, `serve-once!` pending-response plus
successor-cursor retention, malformed-request policy, `dao.stream.apply`
envelope ownership, and the ws-event mapping. Insertion is additive: name
the anchor in the plan's V1 phase and the exact text. This does not reopen
the plan's settled items.

**(c) USER-APPROVED deferral bullet — propose the final wording.** In the
plan's *Not in this plan* section, add:

> - **Internal state as streams** — the log-structured CESK end-state,
>   ready-queue-as-stream, stream fusion, and the store-the-irreducible
>   storage invariant explored in
>   [`yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md).
>   The port keeps scheduler queues as plain data by design (the note's own
>   calibration: synchronous-singular consumers pay boundary tax); those
>   tiers get their own plans after V6 lands.

Adjust phrasing if the section's voice requires it; the content is approved.

**(d) USER-REQUESTED: review the 2026-09-03 amendment set, then a formal
sign-off verdict on the transport plan.** You have not seen these
amendments — sol authored them, gemini-3.1-pro-high adversarially reviewed
(r1: one Critical stranding finding plus three objections), sol revised
(r2), gemini unanimously ACCEPTed, and the orchestrator applied them:

- `dao.stream.md`: the attachment-identity bullet now authorizes
  transport-minted identities for handles no `attach!` produced.
- `dao.stream.ws.md`: acceptance is a bounded, acknowledged handoff (fixed
  pool of capacity-one offer/ack slots, no wire `:ws/accept` before the
  composition's acknowledgement, ownership in every failure case); the wire
  contract is `:ws/accept`/`:ws/disclaim` first frames, close codes
  4000/4002/4004, Transit-JSON codec with a restricted portable domain,
  decode-failure teardown.
- `dao.stream.implementation-plan.md`: `make-attacher` in Phase 2, one
  codec behind `dao.stream.transit`, per-sequence bounded
  linearizability oracle (real-time edges only on harness-observable
  non-overlap), complete exclusion manifest with reject-mode permanently
  excluded, Phase 4a ripples and handoff tests, wire gate marked settled.

Review them against the contract and the consensus trail. Then run the
formal sign-off bar on `dao.stream.implementation-plan.md` as a whole —
the same bar you applied to the VM plan: could a competent engineer execute
Phases 1-5 without inventing an architectural decision the plan should have
made. Say SIGN-OFF: GRANTED or SIGN-OFF: WITHHELD explicitly; if withheld,
give the shortest list that would earn it. If a finding in (a)-(c) would
change the transport plan too, say so and the orchestrator will sequence a
follow-up round rather than you re-reviewing twice.

## Settled — do not re-litigate (each cost multiple review rounds)

1. The VM slice builds `ast-walker` only; four evaluators remain to port.
2. The host supplies streams via `:make-stream`; no default.
3. Telemetry is a stub; a non-nil `:telemetry` opt is a construction error.
4. `:stream/take` is removed, not reinterpreted.
5. Reject-mode ring buffers withdrawn: v1 frees capacity only via
   destructive drain; under v2 a full reject-mode buffer is full forever.
6. Totality is the VM's, retention is the composition's.
7. `dao.stream.apply` owns the request/response envelope.
8. Flow control (4d) is deferred out of the transport slice.
9. USER RULINGS 2026-09-03: the two additive build-config exceptions
   (`shadow-cljs.edn` `:yin-repl` build + the four new `deps.edn`
   aliases) APPROVED; the streams-note deferral bullet APPROVED.

## Constraints

- Read-only: no file edits, no heartbeat (orchestrator judges liveness by
  process health), no test runs (documents-only; no v2 code exists yet by
  design), nothing staged or committed.
- Verify every cited file:line against the working tree before writing it.
- Fixes that relocate their defects are this arc's recurring failure mode:
  for every proposed wording, check the surrounding document for statements
  it invalidates.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>

Then, in order:
1. (a)(b)(c): per job, the proposed insertion or replacement — anchor +
   exact text — or "already explicit, verified at <lines>".
2. (d) amendment review: findings as severity | file:line | evidence |
   correction; note what passed.
3. (d) SIGN-OFF: GRANTED or WITHHELD on
   `dao.stream.implementation-plan.md`, with the shortest list if
   withheld.
