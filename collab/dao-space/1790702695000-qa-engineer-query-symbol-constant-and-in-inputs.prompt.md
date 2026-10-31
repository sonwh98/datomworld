Created-GMT: 2026-09-29 17:24:55 GMT
Created-Local: 2026-09-30 00:24:55 +07 (+0700)
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83
# Task: Fix two q bugs — bare symbol constants in patterns; :in inputs through the yin.repl q bridge

Role: QA / Query Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 00:24:55 +07 (+0700) | Status: active | Rationale: OWNER INSTRUCTION (verbatim) "dispatch fixes for both bugs"

Work in /Users/sto/workspace/datomworld (master ba65a4c0). Edits authorized only in the files named below. Do not
stage or commit.

The bugs (orchestrator-reproduced; scratch scripts under
/private/tmp/claude-501/-Users-sto-workspace-datomworld/a219326d-dfd4-48fc-8e2d-813ace18f078/scratchpad/qdemo*.clj,
run with clj -M -i <file>):

BUG 1 — a bare (non-?) symbol constant in a data pattern does not filter, and host vs REPL disagree.
- In a yin.repl session with (defn inc [i] (+ i 1)), (inc 41), (inc (inc 1)), (require 'dao.space.query):
  (dao.space.query/q '[:find (count ?app) :where [?app :yin/operator ?op] [?op :yin/name inc]]) => #{[8]} (every
  application), whereas grouping by ?n shows exactly 3 inc call sites (#{[inc 3] [+ 1] ...}).
- On the host directly: (query/collect (query/q '[:find ?e :where [?e :a foo]] (query/current (query/relation
  [[1 :a 'foo 0 0] [2 :a 'bar 0 0]])))) => #{} (expected #{[1]} under constant semantics).
Task: find the root cause in src/cljc/dao/space/query.cljc (pattern parsing: how non-variable symbols are classified —
constant, source var, rule, wildcard?) AND why the REPL path behaves differently from the host path (check how the
quoted query crosses yin.repl.query's portable-value conversion). Then determine the INTENDED semantics from the
documented grammar (docs/design for dao.space.query / dao.space — find the query spec; also the namespace docstring and
existing tests). If the docs define bare-symbol pattern constants, implement exactly that. If the docs are silent or
ambiguous (constant like Datomic vs refusing the pattern with a clear error), STOP after the diagnosis and report the
options with evidence — do not choose; the orchestrator escalates. In either case a symbol constant must never
silently match everything or nothing.

BUG 2 — :in inputs through the REPL bridge fail.
- (dao.space.query/q '[:find (count ?app) :in ?f :where [?app :yin/operator ?op] [?op :yin/name ?f]] 'inc)
  => "query options must be a map (:yin.repl.query/query-failed)";
- same with a trailing {:view :current} => the same engine-side "query options must be a map".
The q-on-require design (collab/1790669186000-architect-repl-q-on-require.gpt-6-sol.findings.md section 3) requires
"portable scalar :in inputs before that map". Find the root cause (likely the bridge passes inputs where
dao.space.query/q expects its options argument, or the engine's input-arity contract differs); fix it at the cause.
Tests: a scalar :in input works with and without a trailing options map; a :history view plus an input works; wrong
input arity -> :yin.repl.query/query-failed; existing query_test cases still pass.

Allowed files: src/cljc/dao/space/query.cljc, test/dao/space/query_test.cljc, src/cljc/yin/repl/query.cljc,
test/yin/repl/query_test.cljc. Anything else: STOP and report.

For each fix: regression tests that fail before and pass after; prove by temporary mutation (revert, grep). Portable
CLJC (on CLJD #?(:clj ...) is NOT excluded — #?(:cljd nil :clj ...) with :cljd first; no cross-ns #'private access).

Verify and report exactly: kondo on changed files; cljstyle check (say if blocked); focused JVM dao.space.query-test,
yin.repl.query-test, yin.repl.index-test, yin.repl-test; the full clj -M:test; bb test:cljs. Not bb test:cljd.

Write the report to collab/1790702695000-qa-engineer-query-symbol-constant-and-in-inputs.claude-opus-5-5.report.md and give it
as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 5939e397-ce71-4ffa-8bfa-9f5ba06d2a83
Report root causes with evidence, the documented semantics you relied on (cite), fixes, tests, and anything you
stopped on.

## OWNER INSTRUCTION — test first (added before dispatch)
Owner, verbatim: "first write a test to expose the bug".
Order of work is therefore mandatory:
1. BEFORE touching any production code, write the regression tests that expose BUG 1 and BUG 2 (in
   test/dao/space/query_test.cljc and test/yin/repl/query_test.cljc). Run them against the unmodified source and record
   in the report the exact failing assertions (expected vs actual) — this is the evidence the bugs are real and the
   tests catch them. `git diff -- src` must be empty at this point (report it).
2. Only then diagnose and fix (BUG 1 subject to the stop-if-docs-are-silent rule above; if you stop there, still keep
   its exposing test, marked with the question it depends on, and fix BUG 2).
3. Re-run: the same tests now pass.
The query spec to consult first: docs/design/dao.space.query.md (also dao.space.md).
