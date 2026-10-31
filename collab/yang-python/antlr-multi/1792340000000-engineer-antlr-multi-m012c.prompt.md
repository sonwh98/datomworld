Created-GMT: 2026-10-18 01:35:00 GMT
Created-Local: 2026-10-18 08:35:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: antlr-multi m012c — land-prep completion after the m012b report

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi). The m012b round finished
M0+M1+M2 with all three quick-tier lanes green (JVM 4104/285465/0, Node 4074/149821/0, Dart 4022/0)
but committed NOTHING and skipped four landing prerequisites. Read its report first:
collab/1792291000000-engineer-antlr-multi-m012.claude-sonnet-5-5.report.md

DO, IN ORDER:

1. COMMIT the entire finished M0/M1/M2 state to branch antlr-multi (logical commits: M0 build, M1
   IR/exporters, M2 three-host parser, docs; or one commit if cleaner — your call, but commit).

2. MERGE origin/master (it moved: the :pysp scanner-golden regen 37db44da + docs ba197c96 +
   a8b729ef). Resolve conflicts; the scanner_goldens.cljc pins take master's side.

3. THE IN-SLICE GENERATOR RULE (landed in the generator header at 37db44da): your diff touches
   src/cljc/yang/python/antlr/prelude.cljc (the defquoted macro). Any slice touching the prelude runs
   test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj in the same slice. Set its
   `deferred` to your best prediction of which :c4 groups move (defquoted emits an EDN string only on
   ClojureDart — on JVM the quoted form stays a plain def, so your prediction may be that NOTHING
   moves on JVM except… decide; the macro definition itself is new prelude code, so the py manifest
   ADDRESS likely moves even if the scanned answers do not). Run the generator: an abort on an
   unexpected group is a FINDING — report it, do not widen the set to absorb it silently. If :py or
   :pysp moved, the rewrite lands in your commit. Restore `deferred` to #{:pysp} before committing.
   Double-run: byte-identical.
   Then re-derive the manifest dependents if the py manifest address moved:
   test/yang/python/antlr/float_address_test.cljc (bundled root, A, A′, derivation record, prelude-id),
   test/yang/python/antlr/linked_safepoint_test.cljc (the pysp-manifest-golden),
   test/resources/yang/python/dht-import-v1.edn (regenerate via its checked-in generator
   test/resources/yang/python/dht-import-v1.generate.clj; confirm dht-import-test is the only reader),
   test/yang/python/antlr/linked_prelude_test.cljc (the py manifest pin).

4. THE PLAN'S SLOW VERIFICATION: run the focused ANTLR namespaces with DATOM_SLOW_TESTS=1 that the
   m012b report lists as not run (the plan's verification step: python_antlr_test,
   yang.python.antlr.e2e-test, yin.repl.frontend-catalog-test, plus the conformance/cst namespaces).
   Each as a separate invocation (the comma form selects zero tests).

5. REPL SMOKE ON NODE: the report admits no manual `(lang :yang.python/antlr)` run outside in-process
   tests. Run the Node REPL end to end (node target/yin-repl.js or the shadow build equivalent):
   switch to the antlr frontend, submit the filtered comprehension (the owner's even_squares
   example), confirm the prompt returns and the value prints, exit cleanly. If the Dart peer can be
   driven the same way cheaply, do it; if not, say exactly why in the report.

6. STYLE: clj -M:kondo --lint on every file you changed (0 errors required; the pre-existing
   host.cljc require error and the src/clj+cljd joint-lint false positives are known and exempt) and
   cljstyle check on your changed files (the pre-existing store_test.cljc complaint is exempt).

CONSTRAINTS: single-turn completion, no interactive approvals. Commit everything to antlr-multi; do
NOT push, do NOT touch master. collab/ stays untracked. End with: the commit list, the generator
finding (what moved / what aborted), the new pins, the slow-namespace results, the Node smoke
transcript (or the justification), and anything left unverified.
