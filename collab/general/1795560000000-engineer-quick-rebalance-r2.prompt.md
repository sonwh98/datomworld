Created-GMT: 2026-11-24 06:0x GMT
Created-Local: 2026-11-24 13:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved speed plan, follow-up)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2)
Task-ID: 1795560000000-engineer-quick-rebalance-r2

# Task: quick-tier rebalance r2 — split the yang.php quick shard (the new critical path)

Role: engineer. After the P0 rebalance (landed `2862ec95`-era scheduler), the quick tier's
critical path moved: measured in head-nightly 20261123-2124, `yang.php` = 8,008s
(2h13m) of the 2h20m quick wall — 29 namespaces in one JVM; everything else finished in
≤23min. The tier is now hostage to ONE shard again (the exact pattern P0 fixed).

## Mission (branch `fix/quick-rebalance-r2`, base origin/master current)

In `src/dev/quick_sharded.clj` (and/or the subsystem registry `src/dev/subsystems.edn`):

1. Split `yang.php` quick into K independent shards (K=3-4), groups of namespaces — NOT
   var-level (quick vars may share namespace fixtures; namespace grouping preserves
   same-process lifetime per group). Choose grouping to balance MEASURED cost, not
   namespace count: use the head-nightly per-shard timings and, where available, the
   aggregate-test weights (the 518-program aggregate and the 271-PHPT corpus ns are the
   heavy ones — put them in separate groups).
2. Preserve true subsystem membership semantics: groups are scheduling units; the union
   must equal the old shard's namespace set exactly (set equality check in list mode).
3. Also apply the same treatment to any other shard measured >50% of the quick wall
   after the split (check `unmapped`: LaTTe 888s + dao.star + wedge probes — if it
   approaches the new critical path, split it too by the same rule).
4. Non-weakening: no namespace may move out of quick; invocation order within a
   namespace unchanged; `-e :slow` filtering untouched.

## Verification (scoped only)

1. List mode before/after: total namespace set IDENTICAL (set equality assertion);
   per-shard counts and the new balance printed.
2. The two heaviest PHP namespaces each run green in their own fresh-JVM shard group
   (record Ran/failures + wall — expect ~10-25min each based on the aggregate weights).
3. clj-kondo clean on touched files. Quick planner's other shards unchanged (diff).

## Deliverables

Branch pushed; findings
`collab/general/1795560000000-engineer-quick-rebalance-r2.codex-gpt-6.1-terra.findings.md`
(balance table before/after, every rc/Ran line, head sha). Orchestrator countersigns
and lands; the next nightly measures the wall. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
