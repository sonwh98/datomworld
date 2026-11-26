Created-GMT: 2026-11-24 06:0x GMT
Created-Local: 2026-11-24 13:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-approved speed plan, P1 remainder)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1; the node/dart lanes live here)
Task-ID: 1795565000000-engineer-node-dart-residual

# Task: shiva node/dart lane redistribution — kill the ≥42k s node residual and the dart phase wall

Role: engineer. The shiva head-nightly node lane leaves a 22-namespace residual in one
serial process (≥42,101s in the last measured run; longest completed children: yin.vm
31,790s, yang.python 24,651s) — the same single-hostage pattern the JVM quick tier had.
The dart leg pays a ~499s compile + a long Flutter phase (~36,531s historically).

## Mission (branch `fix/node-dart-residual`, base origin/master current)

Driver-side only (`src/dev/node_shard.clj`, `src/dev/dart_shard.clj`, and the lane
scripts under `~/ci/` if the launcher needs the env): NO test files, NO production src.

1. Node: redistribute by MEASURED weight — compile once (already exists), then schedule
   the residual's 22 namespaces as separate weighted jobs on the existing dynamic work
   queue; move the heavy knowns (yin.vm group, yang.python group) onto early slots.
   If `node_shard.clj` lacks weights, add a static weight table derived from the last
   nightly's per-child times with a comment on how to refresh it.
2. Node: enforce a worker cap tied to core count (24-core host, dart leg concurrent —
   default node workers ≤8 while dart runs, or document the current cap if it exists).
3. Dart: one compile per clone (exists); if the Flutter phase is shardable by the
   existing cljd-agg shard mechanism, split it to ≤4 concurrent drivers; if not
   shardable without test edits, STOP that item and record why.
4. Non-weakening: every namespace/inventory entry still runs exactly once; the pin
   publish protocol untouched; failures still fail the lane.

## Verification (scoped only)

1. Dry-run/list of the new node schedule: set equality with the old plan (all 292
   filenames accounted, exclusions preserved), weights applied, no duplicates.
2. One heavy node namespace (yin.vm group's heaviest child) run scoped on shiva with
   wall recorded — proves the schedule's estimate for it is sane.
3. If the dart split is implemented: one shard group compiles+runs green scoped.
4. clj-kondo clean on touched .clj files.

## Deliverables

Branch pushed; findings
`collab/general/1795565000000-engineer-node-dart-residual.codex-gpt-6.1-terra.findings.md`
(schedule listings, weights table, every rc/wall line, head sha). Orchestrator
countersigns; the next shiva head-nightly measures the wall. Never merge to master
yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
