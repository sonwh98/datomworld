Created-GMT: 2026-09-26 11:50:00 GMT
Created-Local: 2026-09-26 18:50:00 +0700
Coding-Agent: claude
Session-ID: 680306e9-1154-435a-b672-efed46a93226

# Task: fix Fable gate findings on M4 slice S4 (manifests)

Role: VM Runtime Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-26 18:50:00 +0700 | Status: active | Rationale: GLM ~5% budget left; claude authorized

Worktree /Users/sto/workspace/datomworld-m4-s4 (branch m4-s4, uncommitted; do NOT commit). Files: src/cljc/yin/vm/linker.cljc, test/yin/vm/linker_manifest_test.cljc only (plus the spec wording amendment named in item 5, docs/design/yin.vm.linker.md ~line 1642). Gate: Fable APPROVE-WITH-FIXES, text in collab/1790430690001-architect-linker-m4-s4-gate.claude-fable-5-1.stdout.log. Fix, smallest diff:
1. P1-1 (linker.cljc ~783): manifest validator is fail-open for a non-map payload (`(when (map? manifest) ...)` returns nil = verified). Make a non-map manifest a defect like sibling `record-defect` (~853). Add a test that a vector/string manifest at a valid address is refused for the right reason.
2. P1-2 (~1007): `declared-discharge` `case` has no default; unknown :kind on a wire obligation throws. Add a default arm returning :unresolved-free with the name and kind, plus an assertion.
3. P2-1: rename profile strings "b2-stack-lowering"/"r1-register-lowering" to "stack-lowering"/"register-lowering" (ledger precedent "ast-to-bytecode"); update all uses and tests.
4. P2-2: add tests for criterion 24 (manifest whose contracts entry names "b1" refuses :contract-mismatch), the `:missing :relowering` branch (~1993), and a record with wrong op or an extra key.
5. P2-3: make :contract-mismatch evidence at the manifest step (~2150) use the same :expected/:actual convention as step 0 (~1504). Also amend the spec wording at linker.md ~1642 ("the B2 stack lowering, the R1 register lowering") to match the rename, and fix the `with-manifest-index` docstring (index merge is link-local).
Rules: Rule R, fail closed, portable cljc (cljd traps), ASCII, 80 cols, cljstyle clean, kondo via `mise exec -- clojure -M:kondo`. Touched-namespace JVM tests only; orchestrator runs the lanes.
Report to collab/1790432200001-vm-engineer-linker-m4-s4-fix1.claude-sonnet-5.report.md.
