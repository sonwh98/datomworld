Created-GMT: 2026-10-12 07:00:00 GMT
Created-Local: 2026-10-12 14:00:00 Asia/Bangkok

# Task: tag the slow deftests so the quick tier runs in minutes

Role: engineer (claude-opus-5-5). Worktree `/Users/sto/workspace/datomworld-srvm-tiers`, branch `tiers-slow-tags` (from master 03587c54). Do NOT `git add`/commit; do not stage `collab/`. Write each `-n ns` literally (zsh does not word-split a variable); foreground only (a command longer than 10 minutes: run it as a harness background task, not with `&`).

Goal (owner request: "tag the slow tests"; slow tests run less often than quick ones): add the repo's existing slow tag to every deftest at or above 5 seconds, so `bb test:clj` and `clojure -M:test -e :slow` skip them and `bb test:slow` / `clojure -M:test -i :slow` run them. The list, measured on jupiter (a 48-core Linux box, each test run alone, seconds / namespace / deftest): `collab/slow-deftests-5s.tsv` (63 rows). The rule is in `docs/agents/build-n-test.md` ("When to tag a test slow"): `^:slow` on the deftest; if the file is `.cljc`, ALSO wrap the body in `(dao.test-slow/guard "name" (fn [] ...))` so Node and Dart print SKIP unless DATOM_SLOW_TESTS=1. Read that doc section and `src/dev/` / an already-guarded test (grep `slow/guard` under test/) to copy the exact idiom and the require (`[dao.test-slow :as slow]` or whatever the neighbours use). A `.clj` file is JVM-only: `^:slow` alone.

Rules:
1. Change ONLY the tag and the guard wrapper. No test logic, no assertions, no reordering. Keep the diff minimal (indentation changes from the wrapper are unavoidable; cljstyle will fix them: run `cljstyle fix` on changed files then `cljstyle check`).
2. Some of those times include a one-off cost shared by the whole namespace (a `use-fixtures :once` setup, a parser load) that the first test in file order pays: e.g. `zero-division-test` 102 s is probably the first test paying for the ANTLR load. Tagging that test alone will NOT make the namespace quick. For each namespace: tag the listed deftests, then MEASURE the quick part (`clojure -M:test -e :slow -n <ns>`, wall time) and the slow part (`clojure -M:test -i :slow -n <ns>`). If the quick part is still above about 20 s because of shared setup, say so in the report with the cause and do NOT hack around it.
3. The Python tests belong to another orchestrator's track (yang.python.antlr.*, yin.repl.python-antlr-test): make the same minimal edit, nothing more, and list those files separately in the report so they can be checked for conflicts.
4. Verify the tiers on the JVM: (a) every tagged test still passes in the slow run (`-i :slow` for each namespace, in the background harness if long: `yin.repl.python-antlr-test` has 10 tests worth about 39 minutes in total: run just that namespace's slow tests once, last); (b) the quick run of each namespace passes; (c) run `clojure -M:test -e :slow -n yin.repl.python-antlr-test` (quick part) and report the time. On Node: compile the changed `.cljc` namespaces (`clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^<regexp of the changed cljc namespaces>$"}'`) and confirm the guarded bodies print SKIP and nothing fails. Dart: the orchestrator runs it.
5. kondo and cljstyle on every changed file.

Report to `collab/1792070000000-engineer-test-tiers.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
A table per namespace: tests tagged, quick-part wall time, slow-part wall time, and whether the quick part is still dominated by shared setup; the list of changed files split into Python-track files and others; exact lane counts; anything not done. Do not claim runs that did not occur.
