Created-GMT: 2026-10-18 08:50:00 GMT
Created-Local: 2026-10-18 15:50:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: antlr-multi m012f — the landing merge (codex has signed off; resolve and verify)

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, tip a18666fb). Codex
gpt-6.1-sol granted READY for M0-M2 at this head (collab/final2-verdict.log). Land-prep: merge master
and verify the combined head.

DO, IN ORDER:

1. MERGE origin/master (4d4fe395 — the value-display landing, the safepoint-s3 landing, docs). TWO
   known conflicts, each requiring BOTH sides:
   - src/cljc/yin/repl/frontends.cljc: YOUR side installs the yang.python/antlr binding on EVERY host
     (the M2 change); MASTER's side adds the :render key to that binding (the value-display loop
     print). Resolution: the binding on every host WITH :render.
   - test/yin/repl/python_antlr_test.cljc: YOUR side made the tests run both langs on every host and
     rewrote the catalog test; MASTER's side added the acceptance rows (the shared packets map:
     even_squares, None, 1+2, string, semicolons, assignment-after-display — the loop-prints test).
     Resolution: keep both — the per-host harness carrying all the packet rows.
   Take master's side wholesale for scanner_goldens.cljc, float_address_test.cljc,
   linked_safepoint_test.cljc, linked_prelude_test.cljc, dht-import-v1.edn, lower.cljc, prelude.cljc
   (your prelude defquoted change should auto-merge — if it conflicts, resolve preserving BOTH the
   defquoted mechanism and master's value/safepoint additions, and say so prominently in the report).

2. THE IN-SLICE GENERATOR RULE at the MERGED head: run
   test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj with `deferred #{}` — master's pins
   (the :py 3968-era, the :pysp 281-era from s3) must hold unchanged (your defquoted adds nothing
   scanned on JVM, proven at m012c). An abort is a FINDING — report, don't absorb. Byte-identical
   double-run. Restore `deferred` to #{:pysp}. If the merge itself somehow moved pins, follow the
   master-side values and report.

3. VERIFY at the merged head (one namespace per invocation): cst-export-test (16/173 expected),
   python_antlr_test (both langs, the packet rows), e2e-test, frontend-catalog-test,
   yang.python.antlr.lower-portable-test, yin.repl.main-test, float_address_test, linked_safepoint_test,
   linked_prelude_test, dht-import-test. Then the Node quick lane (mise exec -- bb test:cljs) and the
   Dart quick lane (mise exec -- bb test:cljd) — on "Patch does not apply", rm -rf build/antlr
   lib/antlr and rerun (known). The value-display acceptance rows must print values through the antlr
   frontend on the JVM (they are in the python_antlr_test rows).

4. kondo 0/0 on the conflict-resolved files; cljstyle check (if denied, say so).

CONSTRAINTS: single turn, no approvals. Commit the merge. Do NOT push, do NOT touch master. collab/
stays untracked. End with: the conflict resolution map, the generator finding, the namespace table,
the Node/Dart results, and anything open.
