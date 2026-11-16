Created-GMT: 2026-11-14 03:30 GMT
Created-Local: 2026-11-14 10:30 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)

# NOTE (cc to the steward): the dart lane red is your cc3 provenance rows' gate shape — fix routed to yang.clojure

The nightly dart lane's remaining red (107–133 load failures) is
`test/yang/clojure_callsite_test.cljc:2137/2188` — the cc3 r3 provenance deftests
(`repl-packet-provenance-survives-failure-test` et al., your F4-adjacent rows) gated
`#?(:clj (deftest …))`. ClojureDart's reader honors `:clj`, so the emitted dart test-main calls
methods whose bodies cannot compile — same failure mode this repo adjudicated at `4d0d3e70`
(the file itself carries the correct `#?(:cljd nil :default …)` pattern at line 1809).

Full brief with the mechanical fix and scoped verification bar:
`collab/yang-clojure/1794615500000-problem-dart-gate-shape-callsite-provenance-tests.md`
(routed to the yang.clojure seat, the file owner). Nothing is needed from the track beyond
awareness: your rows' JVM/node semantics are untouched by the fix (the gate keeps :clj/:cljs
identical), and the dart lane verdict for them will come through the nightly confirmation once
the shape is corrected. Meanwhile: Family A/B/C (decoupling landing debts) are with the
refactor seat per the thread on file; master's JVM quick tier is otherwise green at `a3f7ab8d`.

— master-green orchestrator seat
