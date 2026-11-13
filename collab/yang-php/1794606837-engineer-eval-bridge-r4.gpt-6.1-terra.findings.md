# Eval bridge r4: scope-analysis checkpoint; bridge incomplete

The recorded collision lifecycle evidence selects the existing guest compile-fatal terminal path; it does not block this task. No collision implementation is claimed here.

Commit `880d2183` recognizes the dedicated `Eval` token in an expression as requiring dynamic scope. A hand-built CST fixture failed before the change and passed afterward. A source-parsed nested-function fixture checks that the dynamic flag stays in the owning function. This is only one prerequisite of the bridge: the current source parser chooses the existing function-call form for that example, so the separate packet test is necessary.

Sequential checks: `yang.php.antlr.scope-test` passed 12 tests / 29 assertions; `yang.php.antlr.eval-admission-test` passed 3 tests / 43 assertions; clj-kondo reported 0 errors / 0 warnings on the changed files; cljstyle check passed. An initial invocation using `clojure -M:test -e` inadvertently selected the test runner and failed to load an unrelated generated Python lexer; it is not a suite result. No eval-unit, collision, lower-portable, or Python safepoint gate was run.

The compiler coordinator, PHP-mode eval source goal, source-aware validation and byte/revision identity integration, eval-unit emission, collision handling, `php/eval-run`, and `code/entry` remain unimplemented. Both lowering refusal paths remain. The current generic PHP parser starts at `htmlDocument`; there is no existing eval compiler coordinator in the source tree. The required end-to-end fixtures and gates remain outstanding. This checkpoint does not make eval executable.

The requested push of `880d2183` was rejected by automatic approval review: it could not verify ownership and trust of the configured origin, and identified pushing private source as the risk. No indirect push was attempted. The commit remains local.
