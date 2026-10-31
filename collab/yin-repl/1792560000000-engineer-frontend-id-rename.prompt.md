Created-GMT: 2026-10-18 13:35:00 GMT
Created-Local: 2026-10-18 20:35:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: retire the :yang.python/antlr frontend id — the Python frontend becomes :yang.python

WORK TREE: /Users/sto/workspace/datomworld-idrename (branch frontend-id-rename, HEAD = master
c5efdf52; the generated parser is BUILT and node_modules are INSTALLED — the lanes can run). The
owner's ruling: ":yang.python/antlr should be removed and the documentation should be updated."
The decision (the orchestrator's, per the F2 naming precedent left unanswered): the frontend id
becomes `:yang.python` — the language-scoped keyword, no implementation suffix. `(lang :python)`
and `:yang.python` are the surface; ANTLR and revision f4 become internal details named only in
implementation notes. The implementation NAMESPACES (yang.python.antlr.*) are NOT in scope — they
keep their names.

THE CODE SURFACE (surveyed; expand if you find more):
- src/cljc/yin/repl/frontends.cljc — the manifest id (:yang.python/antlr → :yang.python) and the
  install. The manifest's :yang.frontend/id semantics must stay coherent (check yang.frontend.md or
  the frontend spec for whether the id feeds any hash/address — if it does, STOP and report; pins
  must not move silently).
- src/cljc/yin/repl.cljc — the references (the lang command's handling, any dispatch on the id).
- Tests: test/yang/python/antlr/e2e_test.clj, test/yin/repl/dht_process_test.clj,
  test/yin/repl/frontend_catalog_test.cljc, test/yin/repl/python_antlr_test.cljc — update the ids;
  check the "Switched to" assertions (the smoke shows the lang command prints the LANGUAGE, "Switched
  to Python", not the id — verify nothing asserts the old id string in output).
- The lang command's user-facing acceptance: (lang :python) AND (lang :yang.python) must both work;
  (lang :yang.python/antlr) now fails the way any unknown id fails (honest unknown-frontend error).

THE DOCS (the owner's explicit second half):
- docs/design/yang.antlr.md — every user-facing :yang.python/antlr reference: the F2 frontend-id
  section, the 4.1 table, 5.5, the §8.5.6 amendment, the §1 starting points. The id becomes
  :yang.python; ONE implementation note (in 4.1 or F2) records: implemented by the ANTLR multi-target
  parser (revision f4), the namespaces yang.python.antlr.*, the id renamed from :yang.python/antlr
  at the migration gate's completion (2026-10-18). The design statements about the distinct
  :yang.python/antlr and :yang.python/legacy ids move to past tense (the retirement + the rename).
- src/cljc/yin/vm/docs/yin.repl.md — the catalog section: :yang.python, with the same one-line
  implementation note.
- The README/architecture pointers (src/cljc/yang/docs/) if they name the id.
- Historical records (docs/orchestrator-log.md, archive/, the known-gaps rows' prose) are
  append-only history — DO NOT rewrite them.

VERIFY (the lanes SEQUENTIALLY — the documented race): frontend_catalog_test, python_antlr_test,
main_test, repl_test, e2e-test, the JVM quick tier (mise exec -- bb test:clj), the Node lane
(bb test:cljs), the Dart lane (bb test:cljd). kondo 0/0 + cljstyle on the changed code files. No
fixture/golden/pin may move — if ANY address or golden changes, STOP and report (the frontend id
must not feed the module manifests).

CONSTRAINTS: single turn, no approvals. One commit to branch frontend-id-rename. Do NOT push, do
NOT touch master. End with: the rename map (old → new, every file), the lang-command behavior
evidence (both selectors), the confirmation that no pin moved, the lane table, and the docs'
implementation-note text.
