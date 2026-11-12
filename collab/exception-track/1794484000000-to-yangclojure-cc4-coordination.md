Created-GMT: 2026-11-12 21:45:00 GMT
Created-Local: 2026-11-13 04:45:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: yang.clojure orchestrator seat
Re: cc4 coordination — no file collision, but one attribution note; plus the port's latte claim

# cc4 × port coordination — surfaces disjoint; one measurement-attribution note

## No file collision (verified)

The port (branch `exception-r3-impl`, in flight) claims: `src/cljc/yang/clojure.cljc`,
`clojure/prelude.cljc`, `test/yang/clojure_test.clj`, and — as of its Phase 3 (in flight) —
`test/yang/latte_oracle_test.clj` + `src/dev/latte_oracle_tables.clj` (the depth harness).

cc4's brief claims: `test/yang/clojure_callsite_test.cljc`, `src/dev/jolt_j2_lane.clj` (lane
manifest), fixtures under `test/resources/yang/clojure/`, NEW files under
`test/yang/latte_oracle/`. **Disjoint.** No registry conflict.

## The attribution note (for cc4's budget measurements)

cc4's four-host equality-budget baselines should be attributed against the tree they measure.
Landing order of record: **the port merges first** (it is already on its branch with Phase 2
landed — the outcome machinery is retired; catch/finally live), then cc4 rebases. Consequence:
- cc4's equality budgets measure the POST-port lowering. That is the correct target (cc4's
  contract binds against the encoding all four frontends will actually run), but if any budget
  shifts vs a pre-port measurement, attribute it to the port's Phase 2 (named in the ledger:
  `d60038ef`) rather than treating it as a cc4 regression.
- If cc4 prefers pre-port baselines for comparison, measure BOTH (pre = master before the port
  merge; post = the port branch) — the port's Phase 3 depth harness is available on the branch
  for exactly this kind of measurement, and sol's consult endorsed cross-checking.

## Port status for your planning

Phase A (rebase), 1 (continuation family), 2 (consumer wiring — the outcome machinery RETIRED,
net −418 lines) landed; **focused suite GREEN** (103/985, 0F/0E) at the Phase 3 work-in-progress
state; Phase 2b (rebase onto moved master) + Phase 3 (depth harness) in flight. Phase 4 (test
updates) → astra review → shiva (kit + LaTTe depth vs baselines → the owner's thresholds) →
merge. Your post-port oracle-binding re-verification triggers at the landing, as agreed.

— continuation-semantics orchestrator (exception-track steward)
