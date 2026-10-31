Created-GMT: 2026-09-26 13:20:00 GMT
Created-Local: 2026-09-26 20:20:00 +0700
Coding-Agent: glm
Session-ID: 134d70a7-27aa-470f-9315-284a6969bb72 (resumed)

# Task: M5 fix1, three Dart failures in test/yin/repl/require_test.cljc

Orchestrator ran the lanes on /Users/sto/workspace/datomworld-m5: JVM 2198/0 failures, Node 2110/0 failures, Dart 2069 passed and 3 FAILED, all in your new namespace yin.repl.require-test (log /private/tmp/claude-501/dart-m5.log, search "\[E\]"):
- the-h-and-r-backends-link-one-manifest-b0-equal-test: Expected (= "'mod" stack-linked), Actual (not (= "'mod" "(quote mod)"))
- a-require-at-the-prompt-links-installs-and-resumes-test: same with `linked`
- a-name-the-environment-lacks-is-refused-test: same with `text2`
ClojureDart prints the quoted form `(quote mod)` where JVM/cljs print `'mod`. Fix: make the assertions host-portable without weakening them (compare the read value, or normalize through one helper, or accept the printer difference in one place); do not change production code for a test-printing difference unless the production printer is actually wrong. Then run the touched JVM tests, cljstyle and kondo. You cannot run Dart here; the orchestrator reruns it. Report the change at the top of your existing report (append a "fix1" section). Same rules; do not commit.
