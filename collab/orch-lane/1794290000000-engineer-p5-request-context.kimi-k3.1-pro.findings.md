# P5 request context findings

## Delivered

- Bound `$_GET`, `$_POST`, `$_COOKIE`, `$_SERVER`, `$_ENV`, `$_FILES`, and `$_REQUEST` from an explicit, task-local request record. Request collections accept either PHP arrays or ordered entry vectors; missing collections become empty arrays. `$_REQUEST` uses GET-then-POST replacement order.
- Added output-buffer stack operations, ordered response records for headers/cookies/status/body, and headers-after-output diagnostics.
- Lowered both `exit`/`die` parser forms to the runtime exit path. Exit runs shutdown functions, returns its status, and is not reported as an uncaught error.
- Added user error triggering, handler restoration, `getenv`, and `php_sapi_name` registrations.
- Added eight hand-derived P5 fixtures, all marked `:oracle :pending`, plus an independent-task isolation test using two separate VM sessions.

## Verification

- `clojure -M:test -n yang.php.antlr.request-context-test`: 3 tests, 35 assertions, all passing.
- `clojure -M:test -n yang.php.antlr.class-model-test -n yang.php.antlr.objects-test -n yang.php.antlr.generators-test`: 14 tests, 1201 assertions, all passing.
- `clojure -M:kondo --lint` over all changed Clojure files: 0 errors, 0 warnings.
- Managed `cljstyle 0.17.642` check over all changed Clojure files: passing.
- `git diff --check`: passing.

No PHP oracle recording was performed, as requested for this round. No push was performed.
