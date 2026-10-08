Completed-GMT: 2026-10-03 03:29:42 GMT
Completed-Local: 2026-10-03 10:29:42 Asia/Ho_Chi_Minh

- P1 | src/cljc/yin/vm/debruijn_code.cljc:244 | The touched float encoder selects `:clj` before `:cljd`, exposing JVM `Double/doubleToLongBits` during CLJD’s host pass. The same ordering remains at debruijn.cljc:879; debruijn_code.cljc:350 also selects JVM classification. data.cljc:81 explicitly documents that CLJD supplies both features. These are pre-existing defects retained in changed functions. | Put explicit `:cljd` branches first throughout these classification and encoding paths.

- P2 | docs/design/yang.antlr.md:1249 | The requested authoritative §§8.5.5 and 8.5.6 are absent. The diff instead adds an unnumbered float ruling under §8.5; numbered subsections end at §8.5.4. Fidelity to the requested converged ruling cannot be established from this checkout. | Include the authoritative sections before sign-off and reconcile implementation scope against them.

- P2 | test/yang/python/antlr/float_address_test.cljc:303 | Execution coverage checks projected and decoded programs, but does not exercise carrier-bearing snapshot/continuation/heap round trips or directly test lift and pin admission. Those gates changed in engine.cljc:633, and the ruling explicitly requires them at yang.antlr.md:1252–1268. | Add lifecycle tests preserving canonical bytes for integral floats, ±0.0, NaN and infinities through those gates.

Additional scope: vm.cljc:374 introduces JS-only refusal for generic arithmetic and ordering over carriers. This changes generic Yin behavior beyond the available paragraph’s scalar-admission requirement; its test covers only `+`.

`numeric-key` is isolated for C3-S2 deletion: one production caller at prelude.cljc:1197, one export at data.cljc:504, plus declarations and tests. NaN-key parity remains explicitly deferred at yang.antlr.md:2123.

Read-only review completed against HEAD `69e58662`. No suites run; Node, CLJD, kondo and cljstyle remain unverified.