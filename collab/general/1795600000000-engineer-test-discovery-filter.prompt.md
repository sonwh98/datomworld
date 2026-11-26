Created-GMT: 2026-11-24 08:5x GMT
Created-Local: 2026-11-24 15:5x +0700
Dispatched-By: master-green orchestrator seat (interactive; agy cross-review P1, correctness-urgent)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1; jupiter POOL 2 at 6% reserved for the in-flight r4)
Task-ID: 1795600000000-engineer-test-discovery-filter

# Task (URGENT correctness): evict non-test scratch files from test discovery + add post-run var-count reconciliation

Role: engineer. agy cross-review P1 (`collab/general/1795590000000-architect-agy-crossreview.gemini-4-argon-high.findings.md` §1.2/§2.2):
`test/d2_wedge_probe2.clj` (namespace `d2-wedge-probe2`, a labeled zero-coverage
diagnostic) matches the discovery deftest-regex, gets scheduled in the quick `unmapped`
shard, and its `(System/exit 0)` KILLED the shard at 279s in head-nightly 20261123-2124 —
19 namespaces (~128 tests / 4,559 assertions incl. LaTTe, dao.star ×10, yang.call ×6)
silently never ran.

## Mission (branch `fix/test-discovery-filter`, base origin/master current)

DRIVER-SIDE ONLY — do NOT move or edit `test/d2_wedge_*.clj` (not our surface):

1. **Discovery filter** (`src/dev/affected.clj:265-269` `index-entry`, and wherever
   quick_sharded/nightly derive test sets): a file counts as a test suite ONLY if its
   namespace name ends in `-test` (the `deftest`-regex alternative path is removed or
   narrowed to namespaces ending `-test`). `d2-wedge-min-repro` / `d2-wedge-probe2` fall
   out of discovery; zero real test namespaces are lost (verify: the 369→367 delta is
   exactly those two).
2. **Post-run reconciliation** (agy §2.2.2): `quick_sharded.clj` and the nightly slow pool
   must fail the lane when a shard's executed test count is missing (nil summary) or when
   `sum(tests)` across shards is less than the discovered metadata inventory count —
   print the deficit. This converts silent truncation into a loud red.
3. **Slow inventory alignment** (`src/dev/slow_inventory.clj`): filter to namespaces
   ending `-test` so scratch files cannot pollute discovery (same rule).
4. No test files moved, no production src touched, no scheduling semantics changed
   beyond the filter + reconciliation.

## Verification (scoped only)

1. Planner list mode: selected = 367 (369 minus exactly the two d2-wedge files), all
   other shards byte-identical; covered/unique checks pass.
2. The `unmapped` shard's namespace list contains neither d2-wedge file.
3. Simulate the reconciliation: a shard with a nil/short summary fails the lane with the
   deficit message (construct the condition in a test harness run of the checker, or
   demonstrate on a synthetic summary file — do not run the full tier).
4. clj-kondo clean on touched files.

## Deliverables

Branch pushed; findings
`collab/general/1795600000000-engineer-test-discovery-filter.codex-gpt-6.1-terra.findings.md`
(listing diffs, the reconciliation demonstration, head sha). Orchestrator countersigns
and lands; the confirmation nightly then restores the ~128 lost tests and LaTTe/dao.star/
yang.call coverage. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
