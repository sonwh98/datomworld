Created-GMT: 2026-11-11 06:40:00 GMT
Created-Local: 2026-11-11 13:40:00 +0700
Coding-Agent: muse
Session-ID: pending (provider-generated)

# Task: cc3 resume-by-state — complete and verify the interrupted round

Role: VM Engineer (compiler provenance)

Implementers:
- Status-Event: 2026-11-11 14:0x +0700 | Model: muse-wonder-1.0 | Status: failed | Rationale: second consecutive provider 429 at turn start (same quota window, reset timestamp stale); zero new output — the lane is walled, not the model's work
- Model: glm-5.3 | Assigned: 2026-11-11 14:0x +0700 | Status: active | Rationale: lane switch after two muse quota failures; flat subscription; VM-work rated per team.md; the brief is model-agnostic (resume by state)

## State (read this first)

Your prior session (32f932e8) was implementing cc3 in worktree `/Users/sto/workspace/datomworld-cc3` (branch `cc3-lowering-provenance`, base `ced9f2c2`) and DIED MID-ROUND on a quota error. The work it left is IN THE WORKTREE, uncommitted: 8 files (+642/−31) — linearize.cljc, derive.cljc, publish.cljc, repl.cljc? (check git status), clojure_callsite_test.cljc, latte_oracle_test.clj, NEW test files derive_test.cljc/linearize_test.cljc. Its PARTIAL findings: `/Users/sto/workspace/datomworld-cc3/collab/yang-clojure/1794357169000-engineer-cc3-lowering-provenance.muse-wonder-1.0.findings.md` — the PREFLIGHT BOUNDS section is COMPLETE (treat it as recorded; do not redo it unless a measurement is wrong), the per-item dispositions and verification are STUBS.

The original brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794357169000-engineer-cc3-lowering-provenance.prompt.md` — re-read it; all seven items, bounds, and invariants still govern.

## Your job

1. **Audit the left diff hunk-by-hunk against the seven items.** Assume NOTHING about its correctness — your predecessor's work is unverified. For each item: complete it if unfinished, fix it if wrong, and write the disposition (file:line) in the findings.
2. **Red-first for anything you add or fix** (new tests failing before the fix, per the brief).
3. **Complete the verification section**: the battery (`clojure -M:test -n yang.clojure-callsite-test`), the touched semantic/publish test namespaces, the narrow oracle deftest selection proving binding + bijection + refusals, kondo on all changed files (0 errors). Exact counts.
4. **Finish the findings file** (same path, it is yours): per-item dispositions, verification table, the datom-lane decision, unverified items. The preflight bounds section stays as recorded unless a number is wrong (then correct it with the new measurement).
5. Bounds/invariants/forbidden files: exactly as the original brief. Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
