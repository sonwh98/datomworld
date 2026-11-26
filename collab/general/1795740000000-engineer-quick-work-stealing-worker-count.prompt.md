Created-GMT: 2026-11-26 04:0x GMT
Created-Local: 2026-11-26 11:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved concurrency fix)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, credits reset)
Task-ID: 1795740000000-engineer-quick-work-stealing-worker-count

# Task: make the quick tier actually concurrent — work-stealing + measured worker count

Role: engineer. Owner-approved. The confirmation nightly measured the quick tier at
5,047s wall with 4,630s of worker-seconds — ~7.7% slot utilization — because
`src/dev/quick_sharded.clj/run-shards!` (~line 107-116) pre-binds each worker to a STATIC
chain (`(take-nth workers (drop w ordered))` after sorting shards by namespace count).
A heavy shard and a 9-second shard chained to the same worker hold the tier open while
other slots idle. agy flagged exactly this (`1795590000000-…findings.md` §1.2.2).

## Mission (branch `fix/quick-work-stealing`, base origin/master `752a35e9+`)

1. **Replace the static chains with a work-stealing queue** in `run-shards!` — same
   pattern as `nightly.clj/run-pool` (at-most-N in flight over a shared ordered
   sequence). Workers pull the next shard when free; no pre-assignment.
2. **Order shards LPT before the pool** (descending measured weight — reuse the weight
   approach from the slow-side LPT round `752a35e9`/its weight table; quick-shard
   weights from the head-nightly shard walls: dao.* ~9-107s, yang.php-e2e-aggregate
   ~1701s, phpt ~748s, yang.php-e2e-rest ~5559s, yang.php main ~8008s historical —
   use THIS confirmation run's per-shard walls as the receipts, they are in
   `~/ci/nightly-results/20261125-2114/` and the run dir summary).
3. **Worker count**: raise the default from 12 to a value defensible from the host:
   48 cores, JVMs ~1.3 cores each during compile and ~1.0 during execution, 62GB RAM
   with JVMs ~2.5GB peak — justify 16 or 18 (not 24: memory headroom with the dart/node
   co-tenants absent on jupiter, but GC pressure is real). Make it an env override
   (`QUICK_WORKERS`) with the computed default.
4. Non-weakening: shard inventory/set equality unchanged; rc semantics unchanged;
   reconciliation (from `dc93a9ac`) unchanged; no test files; the var-split and its
   gate untouched.

## Verification (scoped only)

1. Planner self-tests green (`bb -cp src/dev -m quick-sharded-test`) — with the
   work-stealing change the bounded-worker-count test (peak ≤ workers) must still pass.
2. List mode: identical shard set and commands, only ORDER differs (multiset equality).
3. Modeled makespan: LPT list-scheduling arithmetic with the confirmation-run per-shard
   walls at 12 vs the new default worker count — report both (the confirmation run gave
   the real per-shard walls: use them, not estimates).
4. A REAL short-run demonstration of concurrency: run the planner scoped on a small
   subset selection (e.g. 4-6 dao.* namespaces via the subset mechanism) with workers=4
   and show ≥3 JVMs in flight concurrently (process sampling during the run) — proves
   the pool actually overlaps.
5. clj-kondo clean.

## Deliverables

Branch `fix/quick-work-stealing` pushed; findings
`collab/general/1795740000000-engineer-quick-work-stealing-worker-count.codex-gpt-6.1-terra.findings.md`
(before/after orderings, modeled makespans at 12 vs new count, the concurrency
demonstration, every rc line, head sha). Orchestrator countersigns and lands; the next
nightly measures. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
