Independent audit verification prompt for Lead Reviewer / Auditor (`gpt-6.1-sol`):

Target: Resolution of P1 and P2 findings from `collab/yang-clojure/1793953025937-reviewer-h3-6-audit.gpt-6.1-sol.findings.md` across:
- `test/yang/latte_oracle_test.clj`
- `test/yang/latte_oracle/slice9_evidence.edn`
- `test/yang/latte_oracle/slice9_site_tables.edn`

Your earlier audit findings were:
1. P1: Canonical source hashing depended on raw reader-generated symbols (`p1__...#` in NBE source), causing 60/155 fixture hashes to drift across fresh processes and dropping credited arities to 46/165.
2. P2: Case 6 skipped its dynamic-site refusal assertion because fixture `f2` lacked dynamic sites.

Changes implemented:
1. `kernel-canonical-source-hash` now normalizes auto-gensyms (`^[a-zA-Z0-9_.-]+__\d+(?:__auto__)?#?$`) by order of first appearance into sequential `$gensym_1`, `$gensym_2` tokens. Added fresh-process regression tests in `test-kernel-canonical-source-hash`.
2. Case 6 now uses `:nbe-beta` and asserts that dynamic sites are executed in both lanes and unconditionally refuses unlinked attribution.
3. Both companion evidence files (`slice9_evidence.edn` and `slice9_site_tables.edn`) were regenerated with normalized hashes.

Audit Tasks:
1. Verify that `kernel-canonical-source-hash` is strictly deterministic across fresh reads and independent JVM processes.
2. Verify that `test-attribution-battery` Case 6 unconditionally asserts the dynamic-site refusal rule.
3. Verify that the regenerated evidence files maintain full 165/165 arity credit closure with 0 cross-arity borrowing and intact site rows.
4. Issue a formal audit verdict on whether H3-6 audit is now PASSED.
