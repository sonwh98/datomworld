Created-GMT: 2026-11-26 03:3x GMT
Created-Local: 2026-11-26 10:3x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, credits reset)
Task-ID: 1795730000000-engineer-slow-lpt-ordering

# Task: LPT (longest-first) ordering for the nightly slow queue — stop the alphabetical tail waste

Role: engineer. Live evidence from the running confirmation nightly: the slow tier is in
its alphabetical tail — 3 heavy jobs running, load 2.53 on 48 cores, ~9 worker slots
idle, while `yang.php.antlr.e2e-test` var-shard groups (hours each) and other monsters
started LAST. agy Priority-5 (`collab/general/1795590000000-…findings.md` §2.3.3): heavy
`corpus-test` (8,959s) sits at positions 47–50, `safepoint-harness` (5,078s) at 67–70
of 76.

## Mission (branch `fix/slow-lpt-ordering`, base origin/master `10fccf2e+`)

In `src/dev/nightly.clj` slow-schedule path only:

1. **Weight table** (driver-side, committed with refresh comments): seed with the
   measured historical walls —
   `yang.php.antlr.e2e-test ~29887` (per-group after var-sharding: divide by group
   count), `yang.typescript.antlr.corpus-test 8959`, `yang.php.antlr.include-test 9130`,
   `yin.vm.semantic.safepoint-harness-test 5078`, `yang.typescript.antlr.e2e-test 2754`,
   `yang.call.php-exception-test 2501`, `yin.repl.main-test 116*slow-ratio` (use nightly
   evidence), `yang.python.antlr.e2e-c1/e2e-c2`, `yang.call.python-exception-test`,
   `dht-import`, etc. — every namespace in the slow manifest gets a weight; unknowns
   default to a mid value (not 1 — the agy node-round's default-1 mistake put heavy jobs
   last). Comment each with the run receipt it came from; mark the table as
   refresh-from-NDJSON-once-`10fccf2e`-data-exists.
2. **Sort the schedule LPT** (descending weight, ties by name) before the worker pool —
   both in `lane-jvm` execution AND `--list-slow` (fix the PersistentHashMap-vs-sorted-map
   inconsistency agy flagged: both paths must print the same order).
3. **Weight-aware var-shard groups**: where var-sharding splits a namespace, each group's
   weight is its vars' share; do NOT reorder vars within a namespace's intact constraints.
4. Non-weakening: rc semantics, inventory membership, reconciliation (from `dc93a9ac`),
   var-sharding enablement, and everything else unchanged. This is an ORDERING change.

## Verification (scoped only)

1. `--list-slow` before/after: identical job SET, different order — first 12 after =
   the measured top-12 heavy jobs (list them with weights in findings).
2. Modeled makespan: with the measured weights, compute finish time under 12 workers
   alphabetical vs LPT (simple list scheduling) — report both numbers.
3. clj-kondo clean on touched files.
4. No full nightly run (one is in flight; the change lands for the NEXT one).

## Deliverables

Branch `fix/slow-lpt-ordering` pushed; findings
`collab/general/1795730000000-engineer-slow-lpt-ordering.codex-gpt-6.1-terra.findings.md`
(weight table with receipts, before/after orderings, modeled makespans, head sha).
Orchestrator countersigns and lands; the next nightly measures. Never merge to master
yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
