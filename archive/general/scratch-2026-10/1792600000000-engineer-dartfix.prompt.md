Created-GMT: 2026-10-18 15:20:00 GMT
Created-Local: 2026-10-18 22:20:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: the interactive Dart REPL — the missing :yang/python frontend and the suspect default evaluation

WORK TREE: /Users/sto/workspace/datomworld-dartfix (branch host-command at 05a28f19; the parser is
BUILT, node_modules are INSTALLED, and the cljd build for the interactive entry is DONE —
`dart run bin/yin_repl_main.dart` runs). The owner's live test of the interactive Dart REPL found:

1. `(host)` → `Host: Dart` (correct).
2. `(lang :yang/python)` → `Error: unavailable-parser: Yin REPL language :yang/python is not in the
   frontend catalog` — the ANTLR Python frontend is NOT in the interactive Dart catalog, though the
   M0-M2 landing made it installed on every host and the Dart TEST lanes pass (they select frontends
   through the catalog fixture directly, which is why they stayed green — the interactive
   create-state/standard path on Dart is the untested surface).
3. `1 + 2` under the default frontend after the refused switch printed `2` — under :yang/clojure
   that form should either print 3 (if some reader leniency) or throw; `2` is unexplained. Run it
   down: which frontend actually served that evaluation, and why.

DO:
1. REPRODUCE interactively on Dart exactly as above; capture the outputs.
2. DIAGNOSE why the standard catalog on Dart lacks :yang/python: read src/cljc/yin/repl/frontends.cljc
   (the install's conditionals — the antlr entry's install may be JVM/Node-gated or its install may
   fail silently on cljd), and the frontend/install path for a Dart-specific refusal. The M2 test
   evidence (python_antlr_test on Dart) shows the parser and lowering WORK on Dart in-process — the
   gap is the interactive catalog composition.
3. FIX: the interactive Dart REPL must have the same catalog as the JVM and Node interactive REPLs
   (:yang/clojure default, :yang/php, :yang/python) and `(lang :yang/python)` must switch and evaluate
   (the comprehension/value-display path included). If a genuine Dart limitation forces a difference,
   surface it as an honest refusal with the reason, not a missing catalog entry — and report it as a
   finding either way.
4. Run down the `1 + 2` → `2` observation: after the fix, `1 + 2` under :yang/clojure and under
   :yang/python must print 3 on Dart. If the pre-fix `2` was a real evaluation bug in the cljd
   backend, fix it or record it precisely.
5. TESTS: an interactive-path test for the Dart catalog (the standard catalog on Dart holds all three
   ids and (lang :yang/python) switches) — cljd-runnable; plus the JVM/Node guards that the same
   holds there.

VERIFY (lanes SEQUENTIALLY): the JVM focused namespaces (frontend_catalog_test, repl_test), the JVM
quick tier, the Node lane (bb test:cljs), the Dart lane (bb test:cljd), AND the live interactive Dart
run (`dart run bin/yin_repl_main.dart` with (host) / (lang :yang/python) / 1 + 2 — the captured
transcript). kondo 0/0 + cljstyle on changed files.

CONSTRAINTS: single turn, no approvals. Commit to branch host-command. Do NOT push, do NOT touch
master. End with: the diagnosis (file:line), the fix, the captured Dart interactive transcript after
the fix, the lane table, and anything open.
