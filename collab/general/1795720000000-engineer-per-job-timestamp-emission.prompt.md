Created-GMT: 2026-11-26 03:0x GMT
Created-Local: 2026-11-26 10:0x +0700
Dispatched-By: master-green orchestrator seat (interactive; the enabling prerequisite for the remaining shiva splits)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1; the lane drivers and next run are here)
Task-ID: 1795720000000-engineer-per-job-timestamp-emission

# Task: per-job structured timestamp emission in the shiva lane drivers

Role: engineer. This is the enabling prerequisite the shiva-wall round (`1795700000000`,
landed `f2880dc5`) identified: without per-job start/end/rc/worker records, no node/dart
split can be honestly weighted or verified. Your job is to make the NEXT nightly produce
that data.

## Mission (branch `fix/lane-job-timestamps`, base origin/master `9bd665fe+`)

Driver-side only — `src/dev/node_shard.clj`, `src/dev/dart_shard.clj`, and the nightly
slow pool in `src/dev/nightly.clj` if needed. NO test files, NO production src.

1. **Per-job structured records**: every job (node shard, dart isolate/shard file, slow
   JVM child) writes one record with: job id, namespace/var list, worker id, start
   timestamp (monotonic + wall), end timestamp, rc, wall seconds, and for dart the
   emitted-file path. Format: NDJSON (one JSON object per line) appended to
   `runs-ndjson.txt` alongside the existing `runs.txt`/`schedule.txt` — do NOT replace
   the human-readable summaries, add alongside.
2. **Coverage**: node dynamic queue (work-stealing), dart Flutter invocation (one record
   per emitted shard file with its start/end inside the driver's visibility — if Flutter
   only exposes process-level start/end, record that honestly per-file-queue-position),
   nightly slow children (already partly there via `lane-jvm` logs — normalize to the
   same NDJSON).
3. **Aggregation helper**: a small bb/clj task that reads `runs-ndjson.txt` and prints a
   Gantt-sorted table (start-ordered with overlaps visible) + per-job wall ranking —
   the exact artifact needed to weight the pending splits.
4. Non-weakening: rc semantics unchanged; failures still fail lanes; pin publish
   untouched; no test files.

## Verification (scoped only)

1. Synthetic demonstration: run the aggregation task over a synthetic NDJSON fixture
   (3 overlapping jobs) → correct Gantt order and ranking printed.
2. Node: one real shard invocation emits its record (run the smallest node job scoped).
3. Slow child: one slow JVM child invocation emits its record (smallest slow ns).
4. clj-kondo clean on touched files.

## Deliverables

Branch `fix/lane-job-timestamps` pushed; findings
`collab/general/1795720000000-engineer-per-job-timestamp-emission.codex-gpt-6.1-terra.findings.md`
(record schema, example records, every rc line, head sha). Orchestrator countersigns and
lands BEFORE tonight's shiva confirmation run closes, so the NEXT nightly (or this one's
remaining legs if timing allows) emits real data. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
