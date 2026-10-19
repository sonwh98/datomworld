Created-GMT: 2026-10-18 03:55:00 GMT
Created-Local: 2026-10-18 10:55:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: safepoint s3 (tracing) — finish on top of the landed value-display

WORK TREE: /Users/sto/workspace/datomworld-safepoint3 (branch safepoint-s3). The WIP commit 752647e5
holds the s3 tracing surface (safepoint.cljc, lower.cljc, the five trace fixtures, the e2e/linked/
unit test edits, the yang.antlr.md amendment, trace_programs.cljc — the hook already reformatted it).
Your round-1 report (collab/1792318000000-engineer-safepoint-s3.claude-sonnet-5-5.stdout.log in the
MAIN tree /Users/sto/workspace/datomworld/collab/, also copied as collab/s3-round1-report.log here)
lists the open items. The value-display feature has since LANDED (master 06065e42 + docs c78cc4cc):
the prelude gained py/result! and py.rt/value, the py manifest moved to 093fc404…, and the scanner
:py entry moved to 3968 applications. Your round-1 pins (the pysp manifest 767d8a65…) predate that.

DO, IN ORDER:

1. MERGE origin/master (c78cc4cc) into safepoint-s3. Resolve; keep master's scanner_goldens.cljc and
   value-display code.

2. RE-PIN everything the two stacked prelude moves (yours + value-display's) now change — mechanically,
   from failing assertions or checked-in generators, never hand-invented:
   - the py manifest address (093fc404…) and its dependents: test/yang/python/antlr/float_address_test.cljc
     (bundled root, A, A′, derivation record, prelude-id), test/yang/python/antlr/linked_prelude_test.cljc,
     test/resources/yang/python/dht-import-v1.edn (via its checked-in generator; confirm dht-import-test
     is the only reader).
   - the pysp manifest golden in test/yang/python/antlr/linked_safepoint_test.cljc (pysp requires the py
     manifest; your tracing hooks may also change pysp's own body — recompute, don't assume).
   - THE IN-SLICE GENERATOR RULE (landed in the generator header): your slice touches the prelude — run
     test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj with `deferred` set to exactly the
     groups you predict move (likely #{:py :pysp}; :t only if your tracing touches the reload module).
     An abort on a group you did not predict is a FINDING — report it, don't absorb it. Double-run:
     byte-identical. Restore `deferred` to #{:pysp} before committing.

3. THE ROUND-1 OPEN ITEMS:
   - the yin.repl tests (main-test, frontend-catalog-test, python-antlr-test) — run them.
   - the Node and Dart quick lanes (mise exec -- bb test:cljs / test:cljd) — run them. If a generated-
     artifact patch fails ("Patch does not apply"), rm -rf build/antlr lib/antlr and rerun (the
     generator is not idempotent over already-patched trees — known).
   - cljstyle check on your changed files (if the bare command is denied, say so — the orchestrator
     runs it).
   - the linked-profile tracing fixture (`linked-tracing-profile`, defined but untested per round 1):
     add the test.

4. THE VALUE-DISPLAY GATE'S RIDERS (the deepseek v4 non-blocking notes — fix while you are in these
   files):
   - the provenance docstrings that stop at I6/I1: float_address_test's two docstrings,
     linked_prelude_test's py-manifest docstring, and the scanner_goldens.cljc ns docstring — date them
     through both moves (value-display 06065e42, then your s3 slice).
   - prelude.cljc's py/run-session comment "the value is run-main's" — inaccurate since run-session
     returns :py/value; fix.
   - docs/design/yang.typescript.md:1109 is a 93-column line where the doc wraps near 72; rewrap.

VERIFY (one namespace per invocation): float_address_test, linked_prelude_test, linked_safepoint_test,
python_antlr_test, e2e-test, frontend-catalog-test, dht-import-test, yin.repl.main-test,
yin.vm.semantic.scan-test (slow), plus your safepoint/trace namespaces (DATOM_SLOW_TESTS=1 where they
are ^:slow). kondo 0/0 on every changed file. The generator double-run proof.

CONSTRAINTS: single-turn completion, no interactive approvals. Commit to branch safepoint-s3 (do NOT
push, do NOT touch master); collab/ stays untracked. End with: the commit list, the finding-by-finding
pin map (old → new, with the provenance of each), the generator finding, the namespace table, the
Node/Dart lane results, and anything left open.
