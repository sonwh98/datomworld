Created-GMT: 2026-09-19 14:32:56 GMT
Created-Local: 2026-09-19 21:32:56 +07 (Indochina Time)
Session-ID: pending (provider-generated)
# Task: adversarial architecture review of `docs/design/dao.lease.implementation-plan.md`

Role: Lead Systems Architecture Reviewer

## Under review

`docs/design/dao.lease.implementation-plan.md` — the build spec for
`dao.lease`, currently being implemented against. It has never had an
independent review: it was authored by one model reconciling the two design
documents, and the design documents below it went through a five-model
consensus draft and four adversarial review rounds that the plan did not.
You owe the plan no loyalty; its author is not your family.

## Governing documents

- `docs/design/dao.lease.md` — the operative contract; every sentence a rule
- `docs/design/dao.lease.rationale.md` — binds nothing, but records intent
- `docs/design/dao.stream.md` — the stream contract the facts ride on
- `docs/design/datom.world.md` — the governing axioms and invariants

## Source the plan's decisions cite (read-only, verify the citations)

- `src/cljc/dao/stream.cljc` (outcomes, reader/writer surface)
- `src/cljc/dao/stream/forward.cljc` (the `forward-step` discipline D2 adopts)
- `src/cljc/dao/stream/observe.cljc` (effect-before-commit, D5)
- `src/cljc/dao/stream/ringbuffer.cljc` (the plan's test medium)
- `src/cljc/dao/stream/serving.cljc`, `src/cljc/dao/stream/ws.cljc`
  (the `make-*` assembly-refusal idiom, D4)

## What to test

1. **Accounting.** The plan's §7 end condition claims every rule of
   `dao.lease.md` is covered by an invariant, a decision, a phase, or an
   explicit deferral in §6. Audit that claim rule by rule. Name any contract
   sentence no invariant pins and no §6 row defers.
2. **The D1 ruling.** §0 correction 1 overrides `dao.stream.md`'s
   Composition-section wording ("a pause has to be a lease … whose facts are
   datoms on a medium (`dao.space)"), declaring it a stale cross-reference and
   building facts as plain maps on the `dao.stream` surface. Is that reading
   of the two contracts sound, or does it leave an unresolved contradiction
   between two operative documents?
3. **Invariant fidelity.** Do V1–V5, J1–J11, C1–C6 state the contract's rules
   without weakening them? Watch especially: the ordered cause list, the
   unknown-evidence silence rule, seed-from-grant, record-after-act, cursor
   retirement vs pass abort, and the stamping-with-*now* rule.
4. **Phase decomposition and test adequacy.** Does each phase's "Prove" list
   actually exercise the invariants it claims? Name any J/V/C invariant with
   no scripted test in §4, and any test that could pass while an invariant is
   violated.
5. **Feasibility.** Is `judge-step` as specified (pure, one step, threaded
   ledger, drains to `:dao.stream/blocked`, injected reclaim, `observe/step`
   for record-after-act) realizable against the actual `dao.stream` surface
   cited, or does some step assume an outcome or shape the surface does not
   supply?

## Output

A verdict and a numbered findings list. For each finding: severity
(blocking / should-fix / note), the plan section or invariant it concerns,
and the evidence. End with one line: `VERDICT: <sound | sound-with-findings
| unsound>`. Do not edit anything; this is a read-only review. Produce the
complete review now without waiting for a human.
