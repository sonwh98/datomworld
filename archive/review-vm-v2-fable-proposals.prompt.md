Created-GMT: 2026-09-02 22:54:14 GMT
Created-Local: 2026-09-03 05:54:14 Asia/Ho_Chi_Minh

# Task: Cross-family review — Fable's applied proposals

Role: Cross-family Reviewer (Architect seat holder's own work; non-Claude routing per TEAM.md)

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-03 05:54:14 Asia/Ho_Chi_Minh | Status: active | Rationale: resume of your fixes session 01a06386-e0e1-71e3-ab73-ee8ad23f9af0 — you drafted the V1 Follow-up text Fable amended; you are the right reviewer for the deltas

Repository root: /Users/sto/workspace/datomworld.

## Assignment

Fable (claude-fable-5-1, rotated back to the Architect seat) ran the four-job
round in `collab/architect-vm-v2-round.claude-fable-5-1.findings.md`; the
orchestrator applied all four proposals. Cross-review them. Read Fable's
findings file first, then inspect the applied state via
`git diff docs/design/yin.vm.implementation-plan.md docs/design/dao.stream.ws.md`
(the VM-plan diff also contains earlier unrelated edits from before your
round; Fable's findings file names the anchors — judge the four items below).

1. **V3 precedence alignment** (`yin.vm.implementation-plan.md`, V3 phase):
   "the FFI pair created through it when no explicit `:call-in`/`:call-out`
   pair is supplied" — Fable's fix for a compression that could be misread as
   `:make-stream`-first, contradicting the normative explicit-pair-first rule
   at the FFI-pair section.
2. **V1 transition-algebra insertion** (V1 phase, "**The transition algebra,
   stated once.**" block). Fable adopted your Follow-up draft in substance and
   amended it four ways: (i) added the unsent-retry and unsent-loss-eligibility
   sentences; (ii) added "(recording skipped requests)" to the server's
   request-side `gap`; (iii) added the validation triple so V1 is
   self-contained; (iv) replaced your single ws-mapping sentence with the full
   transition list (`:ws/opened` through unknown-events), scoped as client
   transition data fixed in V1. Verify the amendments did not distort your
   draft's semantics, that the algebra is exhaustive over the contract's
   `append!` and `next` outcome sets, and that it is consistent with the REPL
   plan's mirror (`yin.repl.implementation-plan.md` lines 165-290).
3. **"Not in this plan" two-bullet conversion** with the approved
   streams-all-the-way-down deferral bullet as bullet two.
4. **`dao.stream.ws.md` protocol-failure list addition** (Elements and
   Serialization): "or a `:ws/value` frame received before `:ws/accept` has
   been sent" — Fable's Low finding that a well-formed early value frame had
   unstated handling; the existing decode-failure deposit + close-4002
   teardown now apply. Check it composes with the Handshake's
   no-value-before-accept rule and the acceptance-handoff ordering.

## Constraints

Read-only; no edits; no test runs; the settled nine-item list stands (see
`collab/architect-vm-v2-round.prompt.md`); verify cited lines against the
working tree.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh

Then: 1. Per item 1-4: ACCEPT / ACCEPT-WITH-CHANGES / REJECT, one-line
justification, plus findings (severity | file:line | evidence | correction)
where not a clean ACCEPT.
