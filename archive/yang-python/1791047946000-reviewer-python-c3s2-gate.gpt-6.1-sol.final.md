Completed-GMT: 2026-10-03 17:21:21 GMT
Completed-Local: 2026-10-04 00:21:21 Asia/Ho_Chi_Minh

P2 | test/yang/python/antlr/prelude_parity_test.cljc:531 | The new unhashable test exercises `py/hash`, which bypasses `py/key`’s unhashable arm. Identity-key coverage is JVM-only at e2e_test.clj:533; no portable test exercises set insertion. These tests cannot detect regressions in the preserved key arms across hosts. | Add four-VM portable assertions for identity keys remaining distinct, list/dict/set keys and tuples containing them raising TypeError, and numeric set deduplication retaining the first original key.

P3 | docs/design/yang.antlr.md:2271 | The implementation paragraph still claims three data exports, including `numeric-key`, although its definition and export were deleted. This contradicts line 2296. | Describe the two surviving exports and retain the retired API explanation only in the historical dict-key paragraph.

Static review otherwise found:

- Exact finite decomposition, reduced fractions, signed infinities, and one NaN key consistent with the ruling (prelude.cljc:1278–1338).
- New float key/hash paths unwrap through `data/float-value`; no new host-dependent equality NaN test or unary float negation.
- No executable `numeric-key` references remain; the export-set assertion matches the deletion.
- Existing assertions are preserved except the deliberately retired API tests and updated goldens.
- Master’s `^:slow`, `slow/guard`, and tail-preservation assertions remain intact (safepoint_test.cljc:491). No conflict markers or duplicated conflict hunks found.
- Scope stays within rulings 6–8 and required module registration.

No files edited or suites run. The index still contains the disclosed unmerged entries.

Ready-to-commit verdict: **Not ready until the findings are addressed and the resolved files are staged.**