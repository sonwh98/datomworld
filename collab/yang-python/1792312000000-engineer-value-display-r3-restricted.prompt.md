Created-GMT: 2026-10-17 12:55:00 GMT
Created-Local: 2026-10-17 19:55:00 +07 (+0700)
Coding-Agent: claude
Session-ID: <to-fill>

# Task: Value display — RE-SCOPED: the eval produces the value; the REPL loop prints it

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 19:55:00 +07 (+0700) | Status: active

WORK TREE: /Users/sto/workspace/datomworld-c4fix (branch value-display-r2). Your prior round's uncommitted
changes are present (lower.cljc, prelude.cljc, render.cljc, repl.cljc touched). REWORK THEM per the
re-scope below; the owner has ruled the print belongs at the REPL-loop level, not in the frontend.

THE OWNER'S ARCHITECTURE RULING (verbatim, two messages): "that's not an antlr front end issue. its an
evaluation print issue. eval and print should be par of the REPL loop"; "the print should be part of the
REPL loop and the print should work across all frontend languages because the print should not be in
frontend but in the REPL loop itself".

THE CORRECT SHAPE:
- READ → EVAL → PRINT → LOOP. The EVAL produces the value: the lowering surfaces the last expression
  statement's value as the evaluation's result (the owner's case: `even_squares` alone leaves the list
  as the result). The PRINT is the REPL loop's: main.cljc's evaluation handling prints the evaluation's
  value — the same path for every frontend language (Clojure's expression values already display there).
- Your prior round's IN-BAND MARKER through py/print is the wrong level: the display must not ride the
  program's own output stream through a sentinel. Remove the marker mechanism (the render-layer marker
  branch, the py/print marker lowering). KEEP whatever eval-side value surfacing is sound.

THE RE-SCOPE:
1. **The eval produces the value**: the REPL-mode lowering surfaces the last top-level expression
   statement's value as the evaluation result — a designated result channel (the task's result cell or
   the module body's completion value), NOT through py/print into the output stream. None suppresses
   (a None result displays nothing, per CPython). Assignments/definitions produce no value (Python
   statement semantics).
2. **The loop prints it**: main.cljc's evaluation handling prints the evaluation's value (the repr, via
   the render layer) when present — for EVERY frontend (Clojure and Python at minimum; the display is
   loop code, not frontend code).
3. **The Clojure frontend composes**: its expression values already display — verify the loop-level
   print unifies with (does not double-print) the Clojure path.
4. **Cross-host**: the loop-level print is loop code — it works wherever the loop runs; verify on the
   JVM at minimum and assess cljs/cljd.
5. The prior gate findings that RETIRE with this re-scope: the in-band marker reachability (the
   sentinel is gone), the render-marker branch, the marker collision test. The gate findings that
   REMAIN: the CPython semantics (now every top-level expression statement displays, per the
   orchestrator's ruling "match clojure's: print the last expression evaluated" — the last expression
   evaluated displays; assignments stay silent per Python statement semantics), the cross-host display
   case (the loop prints on every host the loop runs), the idle-tick pin, the stale docstrings.

ACCEPTANCE:
- The owner's case: `(lang :yang.python/antlr)`, `b = 1`, then `b` prints `1`; `even_squares` alone
  prints `[4, 16, 36, 64, 100]`.
- Cross-language parity at the loop: the Clojure frontend's expression values display as before; the
  Python frontend's expression values display identically through the loop-level print.
- None alone displays nothing; an assignment alone displays nothing.
- The prompt-suppress and prompt-owed? regressions stay green.
- Lanes: the focused namespaces, yin.repl.main-test, kondo + cljstyle. No background runs; complete the
  turn.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism (where the value is captured, where the loop prints it), exact counts, anything
behavioral, incomplete work.
