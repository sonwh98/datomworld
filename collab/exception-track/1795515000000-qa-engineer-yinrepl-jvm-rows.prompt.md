Created-GMT: 2026-11-24 03:4x GMT
Created-Local: 2026-11-24 10:4x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed dispatch)
Destination: codex exec -m gpt-6.1-terra on jupiter (debug where the test failed)
Task-ID: 1795515000000-qa-engineer-yinrepl-jvm-rows

# Task: enumerate and fix (surface permitting) the yin.repl quick-shard red rows on jupiter

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-24 10:4x +0700 | Status: active | Rationale: the rows live on the jupiter JVM quick tier; debug on the failing host

## Evidence of record (jupiter quick tier, run 20261123-2124, head `0d378482`)

Shard `yin.repl`: 1 failure, 3 errors (the shard log is in the run dir under
`~/ci/nightly-results/20261123-2124/` per the driver's layout — find the exact rows).
Known candidates from earlier triage: `yin.repl.dht-process-test` rows failing on a fresh
checkout for missing `bb bootstrap:jolt` (`build/tools/jolt-env.sh` absent → child spawn
"Stream closed"; see relay addendum `57d5f33b`), and possible py-pin rows (same generator
cluster as yang.python's 6F/3E).

## Mission

1. Reproduce the shard's red rows in isolation on a fresh-clone worktree on jupiter at
   `origin/master` (note: dht-process rows need `bb bootstrap:jolt` — run the bootstrap
   first so the environment matches the intended lane setup; record whether the rows
   pass WITH the bootstrap).
2. Enumerate each failing row with its exact assertion and root classification:
   (a) lane-setup (missing bootstrap / artifact staleness), (b) py-pin address-mismatch
   (pending yang.php amendment — STOP on those, findings only, no pin edits),
   (c) anything else — a NEW red: classify fully, fix if the surface is
   `test/yin/repl/*` fixtures/setup (NOT pins, NOT yang.php/yang.python files);
   findings-only otherwise.
3. If (a): the fix is driver-side — make the JVM lane's slow/quick path run
   `bb bootstrap:jolt` as a prerequisite (or fail fast with a clear setup diagnostic).
   That is `src/dev/nightly.clj` (master-green lane surface) — you may edit it on your
   branch. Also state whether the test itself deserves a setup guard (that file is
   yin.repl surface — allowed if minimal and non-weakening).

## Verification

1. Every enumerated row: reproduce line, classification, and (for the rows you fixed)
   green in a fresh JVM after the fix — twice.
2. Unrelated yin.repl rows unchanged (the shard's green rows stay green).
3. clj-kondo clean on touched files.

## Deliverables

Branch `fix/yinrepl-shard-reds` pushed; findings
`collab/exception-track/1795515000000-qa-engineer-yinrepl-jvm-rows.codex-gpt-6.1-terra.findings.md`
(row table with classifications, every rc/Ran line, head sha). Countersign and landing by
the orchestrator. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
