Created-GMT: 2026-10-11 10:20:00 GMT
Created-Local: 2026-10-11 17:20:00 Asia/Bangkok

# Task: 5c round 2 -- cover the format record and its registration (fresh session)

NOTE: you are a NEW engineer session (claude-opus-5-5); the round-1 author (codex) ran out of credits and you have no memory of its work. Everything it produced is committed in this worktree and in `collab/`. Read the review first.

Worktree `/Users/sto/workspace/datomworld-srvm-p5c`, branch `srvm-phase5c`, HEAD 58dc0964. Do NOT `git add`/commit. Write each `-n ns` literally (zsh does not word-split a variable); foreground only. Take a verified byte backup of each source file you mutate into `collab/` BEFORE mutating and restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

Read `collab/1791960000000-reviewer-srvm-phase5c.claude-fable-5-1.findings.md` (all findings, the mutant table, and "What is needed before merge"). The scanner code is judged correct; the requested changes:
1. Finding 1 (MEDIUM): mutant F6 (`semantic-register-format`'s `:row-defect-fn`/`:validate-fn` replaced by `(constantly nil)`) survives. Add the record test the reviewer describes (about four `is` forms in `test/yin/vm/linker_test.cljc`): the v4 record's row-defect and validate functions refuse a malformed v4 vector the way the v3 record refuses a malformed v3 one (reuse a malformed vector from `semantic_register/code_test.cljc` or the load-path tests). Apply F6 and confirm it is KILLED.
2. Finding 2 (MEDIUM): mutant F7 (the v4 record removed from `yin.repl.link/formats`) survives. Add a test in `linker_test.cljc` and/or `yin.repl.serve-test` asserting the registration (the `formats` collection contains `semantic-register-format`, and the resolver finds it by its link-format keyword). Apply F7 and confirm KILLED. Do not edit existing assertions.
3. Finding 3 (LOW, equivalent mutant): the `reserved-name?` guard at `scan.cljc:32` is unreachable; replace it with a comment explaining why it is unreachable (source change: say so in the report), or keep it and add the comment; do not delete behaviour without evidence.
4. Finding 4: correct the report's mutant section (a new `collab/` addendum is fine; do not rewrite history).
5. Re-run on the JVM, foreground: `scan-test` and `linker-test` (fast, `-e :slow`), `yin.vm.linker-require-test`, `yin.repl.require-test`, `yin.repl.serve-test` and the semantic-register namespaces; Node for `linker-test` and `scan-test` (fast); kondo and cljstyle on changed files. Do NOT run the slow C4 row (the reviewer already did, JVM 3/208, Node 1/73); do NOT run Dart (the orchestrator does).

Report to `collab/1791990000000-vm-engineer-srvm-phase5c-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change, mutant killed by which named test, exact lane counts. Do not claim runs that did not occur.
