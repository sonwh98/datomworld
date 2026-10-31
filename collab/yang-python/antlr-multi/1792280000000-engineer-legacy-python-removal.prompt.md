Created-GMT: 2026-10-16 21:05:00 GMT
Created-Local: 2026-10-17 04:05:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 7a3042e2-7c56-4e16-ae02-308e0343804f

# Task: Remove the legacy Python compiler; the ANTLR frontend becomes the only Python at the prompt

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 04:05:00 +07 (+0700) | Status: active | Rationale: continuation of your yang.python REPL work in the same files

WORK TREE: /Users/sto/workspace/datomworld-legacy-rm (branch remove-legacy-python, based on origin/master).
Do not touch other worktrees (datomworld-c4fix runs a parallel round; datomworld-c4-i6 runs another). Do not
stage or commit.

OWNER INSTRUCTION (verbatim): "remove. the legacy python compiler and refactor any dependency on it to the
new python compiler" — the owner authorizes removing the hand-written legacy compiler outright. The Phase-1
"legacy remains selectable until the migration gate" rule is superseded by this instruction; note that
supersession in the removal.

SCOPE:
1. Delete src/cljc/yang/python.cljc (the hand-written legacy Python compiler, 532 lines) and its test
   test/yang/python_test.clj.
2. yin/repl/frontends.cljc: remove the [yang.python :as yang.python] require and the :yang.python/legacy
   catalog install. Re-key the ANTLR manifest's language from :python.antlr to :python so
   `(lang :python)` selects the ANTLR frontend on the JVM (the only Python now). Update the std docstring.
3. yin/repl.cljc:894's docstring mentions `:yang.python/legacy` — update to the new selection reality.
4. Sweep for any other yang.python require (grep -rn "yang.python :" src test) — report each and remove
   dead ones; live ones get refactored to the ANTLR path.
5. The consequence to DOCUMENT in frontends.cljc's docstring: Python at the prompt is JVM-only (the ANTLR
   parser is generated Java; Node/Dart answer unavailable-parser for :python) until a Node/Dart parser
   story lands per yang.antlr.md section 5 (local sidecar or remote parser service).
6. The known-gaps/roadmap doc notes: yang.antlr.md's Phase-1 exit criterion ("legacy remains selectable
   until the migration gate") is superseded by the owner instruction — one sentence in the roadmap/notes.

ACCEPTANCE:
- Grep: no yang.python :as requires outside the ANTLR namespaces; no references to the deleted compiler.
- (lang :python) on the JVM selects the ANTLR frontend; the Python e2e/REPL tests still green.
- Lanes: bb test:clj (quick tier), the focused yang.python.antlr namespaces (e2e-test at minimum), kondo +
  cljstyle on touched files, bb test:cljs (the legacy entry was installed on cljs too — its removal affects
  the cljs catalog).
- The Node/Dart Python story: DOCUMENT the gap (Node/Dart now have no Python at the prompt) in the
  frontends docstring + the roadmap note.

LANES: foreground, one at a time, waiting; complete the turn; kondo/cljstyle on every touched file.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the removed/deleted files, every dependency refactored, exact counts per lane, the documented
consequence, incomplete work.
