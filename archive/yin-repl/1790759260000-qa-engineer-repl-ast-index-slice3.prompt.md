Created-GMT: 2026-09-30 09:07:40 GMT
Created-Local: 2026-09-30 16:07:40 +07 (+0700)
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: $ast row relation — slice 3: end-to-end REPL structural-query tests

Role: QA & Verification Engineer

Implementers:
- Model: gemini-3.8-flash | Assigned: 2026-09-30 16:07:40 +07 (+0700) | Status: active | Rationale: team.md (fast TDD/QA); owner directives "use them all" and "work with the team until the current queue is empty"

Work in /Users/sto/workspace/datomworld (master 76038c60: slices 1 and 2 committed). TESTS ONLY: add new test code; do
not change any file under src/. Do not stage or commit. Run every check in the foreground.

GOVERNING DESIGN: collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md, section 5 "Files and
acceptance" and section 4 "Rules" (rules stay opt-in: the caller supplies %; the production root-scoped occurrence
rules and functions live in yin.vm ~1624-1660).
Surfaces under test: src/cljc/yin/repl/ast_index.cljc (slice 1), src/cljc/yin/repl/query.cljc (slice 2: $ast / $occ
supplied when named in :in). Existing tests to learn from (do not duplicate): test/yin/repl/ast_index_test.cljc,
test/yin/repl/query_test.cljc (its q-line helper spells quoted forms as (quote ...)), test/yin/repl/index_test.cljc,
test/dao/space/query_test.cljc ~1121-1161 and ~1415 (free-names and multi-tree occurrence rules).

Write a NEW test namespace test/yin/repl/ast_query_e2e_test.cljc with REPL-level, end-to-end cases on all four VMs
(repl/vm-constructors), each driving real input lines through yin.repl (repl/create-state, repl/eval-input):
1. After evaluating code containing a lambda with a free and a bound variable, a q call with :in $ast $occ ?root % and
   the occurrence-aware free-variable rules (passed as the % input, data only) returns exactly the free names for that
   root. If the rules cannot be passed as portable data through the bridge, STOP and report exactly why.
2. A $ / $ast / $occ join (e.g. a :yin/name from $ joined to the $ast row of the same node address via :yin/address).
3. Identical code evaluated under two different roots: shared $ast rows, distinct $occ occurrences.
4. Re-evaluating identical code adds no $ast rows and no $occ tuples.
5. (reset) and (vm ...) clear the AST relations; a fresh evaluation repopulates them.
6. A lost AST indexer (forced reader gap, as ast_index_test does) refuses $ast queries with index-unavailable while
   evaluation and $-only queries continue, and the round shows the AST warning line.
7. Both query vector and map forms; caller input arity errors; a result over the row limit.
Every test must fail if the behaviour it covers breaks: prove each by a temporary mutation of src (e.g. make the bridge
drop $occ, disable the dedup), run, confirm failure, restore byte-identically, and grep to confirm (git diff -- src must
be empty at the end). If a test exposes a real defect in src, keep the test, report the defect precisely, and do NOT fix
src.
Portable CLJC: :cljd FIRST in mixed reader conditionals; no array-map; no cross-ns #'private; beware the shadow-cljs
compile-time fold trap (a helper with try/catch whose result is str'd at an assertion site can fold at compile time):
return the error object and assert ex-message/ex-data at the call site.

Verify and report, in the foreground: clj -M:kondo --lint test/yin/repl/ast_query_e2e_test.cljc; focused JVM
(clj -M:test -n yin.repl.ast-query-e2e-test -n yin.repl.query-test -n yin.repl.ast-index-test); bb test:cljs; bb
test:cljd. Write collab/1790759260000-qa-engineer-repl-ast-index-slice3.gemini-3.8-flash.report.md and give it as your final
response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: agy
Session-ID: <your conversation id>

## Fix round (2026-09-30 18:00 +07, orchestrator): Architect sign-off findings
gpt-6-sol WITHHELD its sign-off (collab/1790766000000-architect-repl-ast-index-slice3-signoff.gpt-6-sol.findings.md). The orchestrator accepts both findings:
1. MEDIUM | test/yin/repl/ast_query_e2e_test.cljc ~125 | The occurrence test checks each root and path as an independent substring of the printed output, so it does not prove each path belongs to the intended root or shared node. Fix: compare the query result as a set of exact `[root path]` tuples derived from the captured roots.
2. MEDIUM | ~156 | Equal relations and counts across repeated evaluations also pass when both relations stay empty. Fix: after the first evaluation, assert that the expected rows and occurrences exist; after the repeats, compare the full relations.

The refusal test (old requirement 1) was deleted per the fable ruling and must NOT come back.
- Edit only this test file.
- Show a mutation for each fix: the strengthened test must FAIL.
- Run in the FOREGROUND: kondo on the file, the focused JVM test, full `clj -M:test`, `bb test:cljs`, `bb test:cljd`.
Report: collab/1790759260000-qa-engineer-repl-ast-index-slice3.claude-opus-5-5.report-r2.md
