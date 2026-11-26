Created-GMT: 2026-11-24 06:5x GMT
Created-Local: 2026-11-24 13:5x +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec resume -m gpt-6.1-terra, thread 01a20e07-08f8-7261-975e-5ab64a90a608 (same worktree /tmp/quick-reb2)
Task-ID: 1795560000000-r2 (follow-up decision)

# ORCHESTRATOR DECISION: var-level quick split is authorized for yang.php.antlr.e2e-test ONLY

Your stop was correct on the namespace boundary. Resolution, based on evidence already on
file: the slow var-sharding round (findings `1795500000000-…`, landed `2862ec95`) recorded
that `test/yang/php/antlr/e2e_test.clj` declares NO `use-fixtures` and NO `defonce`, and
its slow vars were verified disposition-identical split-vs-intact. The same file, the same
fixture absence — the quick vars of THIS ONE namespace may therefore be scheduled as
separate JVM invocations via the runner's `-n/-v` selectors, reusing the landed
var-sharding mechanism's pattern.

Implement, scoped exactly so:

1. Extend the (already landed) var-sharding support to the quick planner for a
   driver-configurable set containing ONLY `yang.php.antlr.e2e-test` (quick vars, i.e.
   under the planner's existing `-e :slow` selection): split its quick vars into K groups
   balancing the measured weights (the 518-program aggregate and the PHPT-selection test
   each get their own group; measure with a scoped run if in doubt).
2. All OTHER namespaces keep whole-namespace quick scheduling. Set equality: union of the
   split groups' vars == the namespace's quick var set, and every other namespace's
   invocation is byte-identical in list mode.
3. Independence A/B scoped check (mirror the slow round's method): run the two heaviest
   quick vars of e2e-test (a) separately in fresh JVMs and (b) together in one JVM;
   dispositions (Ran/failures + thrown payloads) must match. Record walls for both arms.
4. If ANY quick var shows cross-var state (a shared `defonce` introduced at head, a
   mutated global), STOP and findings-only — do not weaken or reorder anything.

Same bar otherwise: set-equality listing, kondo clean, branch `fix/quick-rebalance-r2`
amended/pushed, findings updated with the A/B evidence. Same deliverable flow.

— master-green orchestrator seat
