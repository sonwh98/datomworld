Created-GMT: 2026-11-14 03:1x GMT
Created-Local: 2026-11-14 10:1x +07
Dispatched-By: master-green orchestrator seat (interactive)
Destination: yang.clojure orchestrator seat (your cc3 surface; fix round theirs or this seat dispatches on request)
Task-ID: 1794615500000-problem-dart-gate-shape-callsite-provenance-tests

# PROBLEM: cc3 provenance deftests use `#?(:clj …)` — the dart lane fails to LOAD `yang.clojure-callsite-test` (107–133 failures)

From: master-green orchestrator seat. Status: **attributed, not fixed.** This is the LAST
unknown red on the nightly dart lane at `a3f7ab8d`.

## Evidence

Nightly dart lane (clean-tree run, `dart-shards-20261113-1746`, all.log): every shard fails at
LOAD — `Compilation failed for testPath=…shard_N_test.dart: test/cljd-out/yang/clojure-callsite-test_test.dart:218:14:
Error: Method not found: 'repl_packet_provenance_survives_failure_test'` (+`…_durable_test`,
`…_survives_link_resume_failure_test`, `repl_fabricated_retention_evidence_is_ignored_*`, …).
The emitted cljd main calls methods the emitted class never defines — deterministic across
runs and across a full generated-tree + `.clojuredart/` cache clean.

## Root cause

`test/yang/clojure_callsite_test.cljc:2137` and `:2188` (cc3 r3 provenance rows, F4 et al.):

    #?(:clj
       (deftest repl-packet-provenance-survives-failure-test …))

ClojureDart's reader does not treat `:clj` as safely exclusive the way the JVM does; the
deftest NAME still reaches cljd's emitted test-main while the body (which uses `java.io.File.`
JVM interop) cannot compile on dart. This is exactly the failure mode this repo already
adjudicated once: `4d0d3e70` ("test(yang.clojure): gate repl-query-link-forwarding-test on
:cljd") established the pattern, and the SAME file documents it at line 1809:

    ;; :cljd first — the publish-name! spy needs with-redefs, which
    ;; ClojureDart lacks …
    #?(:cljd nil
       :default
       (deftest repl-query-link-forwarding-test …))

## Fix (mechanical, your file)

Convert every JVM-only deftest in `test/yang/clojure_callsite_test.cljc` from
`#?(:clj (deftest …))` to the established `#?(:cljd nil :default (deftest …))` shape — search
the file for `#?(:clj` and check each occurrence individually (only deftests whose bodies use
JVM interop need the gate; do not touch non-deftest forms). Verification per the merge bar:
scoped — compile `yang.clojure-callsite-test` on cljd
(`bb src/dev/cljd_agg.clj --only yang.clojure-callsite-test` — if cljd_agg requires an
existing generated file, compile the ns first), then one full dart lane for the lane verdict
(this seat runs it, or coordinate before launching — one dart lane per host). JVM/node must be
unchanged-evidence (the gate keeps :clj/:cljs semantics identical).

Precedents: `4d0d3e70` (the gate), `9032649e`/`d7bd5f85`/`4d0d3e70` (the php-dart gate rounds,
same reader-conditional semantics). Land on a branch; the master-green seat confirms via the
dart lane and folds the nightly verdict.

— master-green orchestrator seat
