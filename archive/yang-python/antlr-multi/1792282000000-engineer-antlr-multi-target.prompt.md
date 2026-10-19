Created-GMT: 2026-10-16 21:20:00 GMT
Created-Local: 2026-10-17 04:20:00 +07 (+0700)
Coding-Agent: claude
Session-ID: <to-fill>

# Task: ANTLR multi-target — JS and Dart parsers for the Python frontend (kill the JVM-only limitation)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 04:20:00 +07 (+0700) | Status: active | Rationale: antlr build integration plus the frontend context you built across the last rounds

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, based on origin/master). Do not
touch other worktrees (datomworld-c4fix, -c4-i6, -c4hang are in use). Do not stage or commit.

OWNER INSTRUCTION (verbatim): "don't remove legacy until antlr works for JS and dart too" — make the ANTLR
Python frontend work on Node and Dart so the legacy hand-written compiler can retire (its removal is a
SEPARATE later round; this round does NOT remove anything).

CONTEXT: today `bb gen:python-antlr` generates 4 Java files from the vendored Python3Lexer.g4/Python3Parser.g4
(Java/Python3LexerBase.java etc. are fetched and checksummed; classes land in build/antlr/python3/classes),
and only the JVM parser worker (src/clj/yang/python/antlr/parser.clj, parse-source) can run the ANTLR
pipeline — the REPL catalog gates :yang.python/antlr JVM-only (yin/repl/frontends.cljc), and Node/Dart
answer unavailable-parser.

THE MECHANISM: antlr4 generates JavaScript and Dart targets from the same vendored grammars. The parse
tree shape across antlr4 targets for the same grammar is the same, so yang.python.antlr.uast (the CST→UAST
converter) should consume the JS/Dart parse trees with the same walking — verify, adapt where the target
shapes differ (e.g. how terminals/ctx classes appear in JS vs Dart vs Java), and keep the vendored-grammar
checksums driving all targets.

SCOPE:
1. The gen task generates the JS and Dart parser+lexer targets from the vendored .g4 files alongside the
   Java ones (build/antlr/python3/js, build/antlr/python3/dart), pinned and reproducible.
2. The parser worker: parse-source equivalent entry points for Node (cljs interop over the antlr4 npm
   runtime + the generated JS lexer/parser) and Dart (cljd interop over the antlr4 Dart runtime + the
   generated Dart lexer/parser), returning the same CST shape parse-source returns on the JVM.
3. The REPL catalog: :yang.python/antlr becomes available on Node and Dart (the frontends.cljc JVM-only
   reader conditional opens to the wired workers).
4. The consequence flips: Python at the prompt is available on all three hosts; the legacy compiler stays
   until a separate removal round (not this one).
5. Acceptance: parse-source parity — the same Python submission produces the same CST→UAST→program on
   JVM, Node and Dart (a parity test comparing the UAST or the program output across hosts); the e2e
   submission path (the prompt-suppress round's repl/eval-input regression) runs through the ANTLR
   frontend on Node and Dart; the owner's comprehension line evaluates at the prompt on Node and Dart
   (the value display from the parallel round composes if available).
6. Documentation: frontends.cljc docstring, yin.repl.md's Python section (the JVM-only statement flips),
   yang.antlr.md section 5 — the Node/Dart parser story recorded as landed.

LANES (foreground, one at a time, waiting; over a cap, rerun in namespace groups and say so; complete the
turn): bb test:clj (quick), bb test:cljs (quick), bb test:cljd (quick), kondo + cljstyle on touched files,
the focused yang.python.antlr namespaces with DATOM_SLOW_TESTS=1. The FULL slow suites on shiva/jupiter are
the orchestrator's job — run only the quick tiers locally.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report the mechanism per host, the CST-parity evidence, exact counts per lane, anything behavioral,
incomplete work.
