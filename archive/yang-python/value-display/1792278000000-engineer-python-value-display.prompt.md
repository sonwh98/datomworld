Created-GMT: 2026-10-16 20:55:00 GMT
Created-Local: 2026-10-17 03:55:00 +07 (+0700)
Coding-Agent: claude
Session-ID: c14fbbd6-f311-493b-b6d7-f05e78bc3dc8

# Task: Value display for the ANTLR Python frontend at the REPL

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 03:55:00 +07 (+0700) | Status: active | Rationale: continuation of your prompt-suppress work in the same files

WORK TREE: /Users/sto/workspace/datomworld-c4fix — FIRST: `git checkout -b yang-python-value-display origin/master` (the worktree carries no other work now). Do not touch other worktrees. Do not stage or commit.

THE DEFECT (owner-reported): at the JVM prompt after `(lang :yang.python/antlr)`, evaluating a bare name or
any expression should print its value (CPython interactive semantics: the repr of every expression
statement's value, via sys.displayhook) — but the frontend displays nothing. It is deliberately
output-only today (`:display :output`, frontends.cljc: "shows what a submission printed and not the
program's value"). CPython parity requires: `even_squares` alone prints `[4, 16, 36, 64, 100]`;
`1+2` prints `3`; an assignment stays silent; an expression at the end of a multi-statement submission
prints its value (CPython: only expression STATEMENTS display, and the LAST one's value per submission
via displayhook semantics).

DESIGN (CPython model): the lowering gets a REPL/interactive mode where top-level expression statements
capture their value (sys.displayhook semantics: repr(value) printed, None suppressed per CPython — a bare
None expression prints nothing). Find the minimal mechanism in python.antlr.repl (the lower-session
already exists) or the frontend binding: capture the value, render it via the existing render layer
(py/repr if present), print it. Assignments and definitions stay silent. Preserve the landed prompt-suppress
behavior (prompt-owed?, the cursor semantics).

ACCEPTANCE:
- The owner's case: after the assignment, evaluating `even_squares` alone prints `[4, 16, 36, 64, 100]`.
- CPython parity cases: `1+2` prints `3`; `None` alone prints nothing; an assignment alone prints nothing;
  the last expression of a multi-statement submission prints once; `print(...)` output unchanged.
- The prompt-suppress regressions stay green (the silent-assignment case must STAY silent).
- Lanes: the focused namespaces (DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n yang.python.antlr.e2e-test
  + the repl-facing test file), kondo + cljstyle on touched files. The Node leg if the change reaches it.
No background runs; complete the turn; mise is trusted; run gen/build if builds are stale.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism, exact counts, anything behavioral, incomplete work.
