Created-GMT: 2026-10-17 12:55:00 GMT
Created-Local: 2026-10-17 19:55:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 0ef46cb0-7541-4de5-9fcf-78eb1b0a8896

# Task: Value display for the ANTLR Python frontend — the owner-ruled semantics

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 19:55:00 +07 (+0700) | Status: active

WORK TREE: /Users/sto/workspace/datomworld-c4fix (branch value-display-r2, clean at 822e938a). Do not
touch other worktrees. Do not stage or commit.

THE OWNER-RULED SEMANTICS (verbatim): "neither. it should match clojure's. it should print the last
expression evaluated" — the REPL prints the value of the LAST EXPRESSION EVALUATED in the submission
(Clojure REPL semantics). Mapped to Python: the last expression STATEMENT's value displays (assignments
are statements, not expressions in Python — an assignment-only submission displays nothing, matching
CPython); a bare name displays its value; the owner's case: `even_squares` alone prints
`[4, 16, 36, 64, 100]`.

A PRIOR IMPLEMENTATION of this feature existed and was lost with a worktree (its session may hold the
diff: the earlier attempt used an in-band marker string lowered through py/print with a render-layer
branch). Its known gate finding to PRESERVE: the in-band marker was reachable — a user
`print("\0display")` collided with the sentinel; harden the sentinel (a Python-unforgeable value — a
non-string object the string path cannot produce — or a per-session nonce) with a collision regression.

DESIGN SKETCH (your judgment within these bounds): the REPL/interactive mode marks the last expression
statement of a submission; the marked statement's value is captured (displayhook semantics: repr via
the render layer, None suppressed); the captured display rides the output path; the frontend prints it.
Assignments/definitions stay silent (Python statement semantics).

ACCEPTANCE:
- The owner's case: `even_squares` alone prints `[4, 16, 36, 64, 100]`.
- CPython parity: `1+2` prints `3`; `None` alone prints nothing; an assignment alone prints nothing;
  `print(7)` prints `7` once; the last expression of a multi-statement submission displays once.
- The prompt-suppress regressions stay green (a silent assignment still shows no value output and the
  prompt contract holds — the landed prompt-owed? fix is in master beneath you).
- The marker collision regression (a user print of the sentinel does not render as a display).
- The cross-host display case in the shared packet fixtures (cljs/cljd exercise a display line).
- Lanes: the focused namespaces (DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n
  yang.python.antlr.e2e-test + the repl test file), yin.repl.main-test, kondo + cljstyle on touched
  files. No background runs; complete the turn.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism, exact counts, anything behavioral, incomplete work.
