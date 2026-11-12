Completed-GMT: 2026-11-11 05:48:35 GMT
Completed-Local: 2026-11-11 12:48:35 ICT

Static confirmation only; no suites run and no files edited.

**F1: OBJECTION: P1 — path translation is fixed, but correspondence verification remains incomplete.**

The wrapper-removal traversal correctly translates nested argument paths, with a regression at [latte_oracle_test.clj:3799](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3799). However:

- [Line 3518](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3518) checks child structure but ignores scalar grammar fields. Changing a callee’s variable name, a literal value, or lambda parameters preserves the accepted correspondence.
- [Line 3493](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3493) strips wrappers using a recognizer that does not verify the helper is `gate-site!` ([line 2029](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2029)).
- [Line 3610](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3610) builds a map before checking ambiguity: duplicate site IDs overwrite earlier occurrences. The subsequent distinct-values check cannot recover that loss.

**Required fix:** verify unchanged grammar data and the complete permitted wrapper transformation; reject duplicate site IDs before map construction. Add same-shape mutation and duplicate-ID refusals.

**F2: OBJECTION: P1 — admission is now recorded before compilation, but identities collide across runs.**

The coordinate is retained and consumed, and the content-only spelling is gone. However, [latte_oracle_test.clj:2451](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2451) introduces a global counter initialized to zero. With constant medium `:program`, two fresh JVMs admitting identical content at the same ordinal produce identical origins ([line 2470](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:2470)). The regression tests only two admissions within one JVM ([line 3843](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3843)). The global atom also conflicts with the repository’s explicit state invariants ([datom.world.md:26](/Users/sto/workspace/datomworld-cc3/docs/design/datom.world.md:26)).

**Required fix:** supply and retain an explicit admission/run scope that survives independent-run distinction; replay must reuse the recorded coordinate. Test separate scopes with restarted ordinals.

**F3: CONFIRMED-FIXED.**

[repl.cljc:193](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:193) selects exactly `[admission root]`, and the claimed-A/unclaimed-B regression is present at [clojure_callsite_test.cljc:2037](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2037).

Production obtains the identity from newly retained expansion entries ([repl.cljc:1964](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1964)). Under the shell’s documented sole-appender, one-batch-per-round invariant ([line 1282](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1282)), this closes the ordinary-path borrowing case.

**F4: OBJECTION: P1 — successful loads retain provenance, but execution failures lose its lookup handle.**

The common wrapper covers every production loader, materializes the artifacts, and successful-path reopen retrieval is tested ([repl.cljc:1484](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1484), [clojure_callsite_test.cljc:2085](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2085)).

The new defect is exception handling: handles accumulate in a local volatile and reach session state only after `observer/run-on-stream` returns ([repl.cljc:1483](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1483), [line 1504](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1504)). If loading or evaluation throws, recovery uses the earlier `expanded` state ([line 1901](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1901)). The provenance artifacts have been written, but their newly created handles are absent from the returned session.

**Required fix:** preserve completed retention results through the exception/recovery path. Add a claimed program that throws after loading and verify its handle remains retrievable after reopening.

**F5: CONFIRMED-FIXED for the explicitly claimed artifacts.**

The post-stamp application census now includes inserted helpers separately and checks exact union/disjointness/counts ([latte_oracle_test.clj:3694](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3694)). The pre-stamp images named by the binding now have exact call-PC coverage and occurrence-frequency comparisons, with Rule-R definitions independently excluded ([line 3742](/Users/sto/workspace/datomworld-cc3/test/yang/latte_oracle_test.clj:3742)).

This confirms the separate helper census and compiled-image mappings; inserted instrumentation helpers are explicitly unmarked, rather than claimed as covered C1 rows.

**F6: OBJECTION: P2 — storage measurements are present, but retained/peak memory remains unmeasured.**

The earlier incomplete-preflight status is preserved, and serialization sizes, timings, and finite ceilings are now recorded ([fix report:140](/Users/sto/workspace/datomworld/collab/yang-clojure/1794374077000-engineer-cc3-fix-r2.muse-wonder-1.0.findings.md:140)).

However, “retained memory” is simply the relation’s canonical serialized byte count ([line 166](/Users/sto/workspace/datomworld/collab/yang-clojure/1794374077000-engineer-cc3-fix-r2.muse-wonder-1.0.findings.md:166)). That does not measure retained JVM objects. The heap figure is one sample after binding ([line 154](/Users/sto/workspace/datomworld/collab/yang-clojure/1794374077000-engineer-cc3-fix-r2.muse-wonder-1.0.findings.md:154)), not peak usage through lowering, persistence, and tagged execution.

**Required fix:** distinguish serialized storage from retained memory, supply a defensible retained-memory measurement, and measure peak usage against the declared ceiling. Preserve the current record’s remaining limitations.

CONFIRMATION VERDICT: REQUEST CHANGES