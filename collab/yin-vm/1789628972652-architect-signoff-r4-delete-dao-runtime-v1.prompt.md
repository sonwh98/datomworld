Created-GMT: 2026-09-17 07:09:32 GMT
Created-Local: 2026-09-17 14:09:32 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 8777feab-1732-4507-a2ab-7990e4d412ee

# Task: Architect sign-off on R4 (deletion half) and the naming-decision edit

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-17 14:09:32 +07 | Status: active | Rationale: sign-off gate before commit, covering two concurrently-produced, independent diffs to the same plan document

Perform a read-only architecture review of two diffs, both against
`docs/design/dao.runtime.implementation-plan.md`'s Phase R4:

1. **The deletion** (8 files removed, 1 edited): read `git status --short`
   for the full list, and `git diff test/dao/test_utils.cljc` for the one
   edit.
2. **The naming-decision prose** (the same design doc, a different,
   disjoint section): `git diff docs/design/dao.runtime.implementation-plan.md`.

## Context

R4's gate opened tonight when `yin.vm.v1-retirement.implementation-plan.md`'s
U6 deleted the v1 VM (and, as a side effect, `runtime_adapter.cljc` and its
tests, already recorded in R4's own status note). This unit is the rest of
R4: deleting legacy `dao.runtime`, its three host drivers, and their tests
(run concurrently with, and independent of, recording R4's own naming
decision in the same doc's prose).

Verified independently by the orchestrator: `clj -M:kondo --lint` clean.
`clj -M:test` (full suite) → 1384 tests, 0 failures. `bb test:cljs` (full
suite) → 1283 tests, 0 failures, `Testing dao.runtime-test` and
`Testing dao.runtime.driver-test` both confirmed present. `bb test:cljd`
(full suite, `test/cljd-out` cleared first) → 1241 tests, all pass;
regenerated `test/cljd-out/dao/runtime/` confirmed to contain only `v2/`
artifacts. A grep sweep for `dao\.runtime(\.driver)?` returns only two
documented non-hits in still-live v1 `dao.stream.cljc` prose, out of scope
(retires with a separate, not-yet-started `dao.stream` v1 plan).

Independently reviewed by an adversarial reviewer
(`collab/1789628785755-review-r4-delete-dao-runtime-v1.gemini-3.1-pro-high.findings.md`):
verdict ready for sign-off, no findings, including explicit confirmation
that leaving the `NonWaitableStream` defrecord in place (only its
constructor `make-non-waitable-stream` was removed) is the correct scope
boundary — it belongs to the separate `dao.stream` v1 retirement, not this
`dao.runtime` cleanup. Treat as a claim to verify, not authority.

The naming-decision addition records, per this doc's own R4 text
("mirroring the stream plan's end condition... An undecided coexistence is
a defect of the migration, not a steady state"): the recommendation to
rename is taken as the decision, but deferred — not undecided — because
its trigger (`dao.stream` → `dao.stream` "when the last consumer has
migrated") is not close, citing a fresh census (~45 live `dao.stream` v1
consumers, no retirement plan drafted).

## Task

1. Confirm the deletion is complete and correctly scoped — spot-check
   independently, don't just trust the reviewer's confirmation.
2. Judge whether leaving `NonWaitableStream` in place (constructor
   removed, record kept) is architecturally sound, or whether it should
   have been removed in this same unit despite the "different retirement
   scope" reasoning both the implementer and reviewer gave.
3. Confirm the naming-decision addition is accurate and consistent with
   the parallel decision already recorded in
   `yin.vm.v1-retirement.implementation-plan.md`'s D5 (which named
   `yin.vm`/`yin.repl` as "the same question and the same answer" as
   `dao.stream`'s and `dao.runtime`'s renames) — read D5 if you need
   to confirm the cross-reference is coherent.
4. Confirm the two diffs are genuinely independent and safe to commit
   together or separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report findings and an explicit APPROVE / APPROVE-WITH-FINDINGS /
REJECT verdict, governing whether the orchestrator is authorized to stage
and commit these diffs. Deliver the actual verdict text directly in this
response now — do not stop to ask permission, and do not reference a plan
file or say the review was delivered elsewhere.
