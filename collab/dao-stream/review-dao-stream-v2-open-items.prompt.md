Created-GMT: 2026-09-02 19:05:45 GMT
Created-Local: 2026-09-03 02:05:45 Asia/Ho_Chi_Minh

# Task: Cross-family adversarial review — dao.stream v2 open-items proposal

Role: Cross-family Adversarial Reviewer (Routine Review seat, non-GPT routing per TEAM.md)

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-03 02:05:45 Asia/Ho_Chi_Minh | Status: active | Rationale: proposal is gpt-5.6-sol-authored; same-family review is ruled out; pro-high carries the context budget for the five-document cluster

Repository root: /Users/sto/workspace/datomworld (you are running in it; paths below are relative to it).

## Assignment

Adversarially review the proposal in
`collab/architect-dao-stream-open-items.gpt-5.6-sol.findings.md` — seven
proposed amendments (A1-A3 to `docs/design/dao.stream.ws.md`, B1-B4 to
`docs/design/dao.stream.implementation-plan.md`). Your value is finding
what a same-family reviewer would not. The orchestrator has verified the
proposal's citations against the working tree; your job is the reasoning:
contract grounding, internal consistency, and consequences.

## Read first

- collab/architect-dao-stream-open-items.gpt-5.6-sol.findings.md — the proposal under review
- docs/design/dao.stream.md — the contract; authority over everything else
- docs/design/dao.stream.ws.md — the spec A1-A3 amend
- docs/design/dao.stream.implementation-plan.md — the plan B1-B4 amend
- docs/design/yin.repl.implementation-plan.md — consumer; lines 340-380 state the blockers this proposal answers
- src/cljc/dao/stream/ringbuffer.cljc lines 100-180 — v1 evidence the proposal cites

## Specific questions the orchestrator wants answered

1. A1 grounds server-minted identity in `dao.stream.md:254-261`, whose wording
   ties `:dao.stream/attachment` to "its `attach!` success map". Is minting
   without `attach!` a faithful reading or a contract amendment smuggled in as
   a spec clarification? If the latter, say what contract text must change and
   whether that is in scope for a subordinate document.
2. A2 + A3 consistency: A2 requires the serving boundary's `:ws/accepted`
   deposit to return `:dao.stream/ok` before the transport sends the wire
   acceptance frame; A3 makes that frame the first server message. Check this
   against `dao.stream.ws.md`'s Deposit Admission rules (every non-`ok` deposit
   tears the connection down). Is the composition deadlock-free when the
   deposit medium is full at acceptance time under evict-oldest? Any ordering
   hole?
3. `:ws/accepted` names both a serving-side deposited event (A2) and a
   client-facing wire frame (A3, `{:ws/frame :ws/accepted}` → client deposits
   `:ws/event :ws/opened`). Two vocabularies for "acceptance" across sides is
   the defect shape this project has burned review rounds on before. Real
   hazard, or acceptable because the key contexts (`:ws/event` vs `:ws/frame`)
   and sides never mix? If a hazard, propose the renames.
4. A3 names close codes 4000 (ended), 4002 (protocol-error), 4004 (not-found)
   and a Transit-JSON subprotocol `dao.stream.transit-json`. Check the
   portable value domain against all three hosts (clj/cljs/cljd) and the
   restricted-integer choice. Anything Node's ws or Dart's `dart:io`
   WebSocket implementation cannot deliver?
5. B3's oracle: bounded linearizability against a pure abstract model. The
   contract (`dao.stream.md:580-586`) guarantees each outcome reflects the
   sequence "at one moment" and "nothing further". Is linearizability the
   strongest *faithful* oracle, or does it assert more than the contract
   promises and thereby fail conforming transports? Consider multi-handle
   writers on one logical stream.
6. B4's exclusion table: contract `dao.stream.md:417-424` allows three honest
   reasons (impossible by nature / another named policy answers it / displaced
   to a named channel). Does every row's reason fall in one of the three
   classes? Is the `create!` row's exclusion of `not-found` ("host dispatch
   answers absence before this handler is selected") consistent with the
   contract's own `create!` outcome table, which includes `not-found` for "no
   transport here matches type"?
7. The proposal's own hygiene: A3 lists `dao.stream.ws.md:202-204` as
   invalidated but supplies no replacement wording for it. Draft the missing
   wording. Check every other "invalidated elsewhere" claim the same way —
   named but unworded fixes are this arc's relocated-defect trap.

## Settled — do not re-litigate

1. The VM slice builds `ast-walker` only; four evaluators remain to port.
2. The host supplies streams via `:make-stream`; no default.
3. Telemetry is a stub; non-nil `:telemetry` opt is a construction error.
4. `:stream/take` is removed, not reinterpreted.
5. Reject-mode ring buffers withdrawn: v1 frees capacity only via destructive
   drain (`ringbuffer.cljc` drain advances `:head`); under v2 a full
   reject-mode buffer is full forever. Do not reintroduce.
6. Totality is the VM's, retention is the composition's.
7. `dao.stream.apply` owns the request/response envelope.
8. Flow control deferred out of the transport slice.
9. USER RULING 2026-09-03: the `shadow-cljs.edn` `:yin-repl` build exception
   is APPROVED.

## Constraints

- Read-only: do not edit any file. The orchestrator captures your stdout and
  promotes it to `collab/review-dao-stream-open-items.gemini-3.1-pro-high.findings.md`.
- Do not run test suites. Documents-only task.
- Judge the proposal as written; do not redesign it. Where you would have
  chosen differently, say so as a note, not a finding, unless the choice is
  unsound.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh

Then:
1. Findings: severity | file:line (or proposal item) | evidence | correction.
2. Answers to questions 1-7 above, numbered.
3. Per-item verdict table: A1, A2, A3, B1, B2, B3, B4 → ACCEPT / ACCEPT-WITH-CHANGES / REJECT, one line of justification each.
4. The drafted wording for question 7, in quotable form.
