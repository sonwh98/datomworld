# Eval bridge r15: incomplete

Added `test/yang/php/antlr/eval_bridge_test.clj`, with the requested return-value
fixture and a caller-variable read, each asserted on all four JVM evaluators.
The focused command `timeout 180s clojure -M:test -n
yang.php.antlr.eval-bridge-test` completed with 1 test, 8 failures and 0 errors:
both sources are rejected during lowering as `:yang.php/unsupported :eval`, so
no evaluator ran either source. The general e2e namespace could not be used for
the first attempt because its TypeScript dependency requires an unavailable
generated `TypeScriptLexer`.

No production implementation was made. The missing work remains the caller
request and response path, admission of dynamically published module names and
addresses, installed-entry invocation with the caller's runtime, scope, context
and hooks, both lowering paths, and composition wiring. The existing linked
harness explicitly excludes the AST walker, so the four-evaluator acceptance
also requires an appropriate dynamic linker composition for that backend. The
existing service test still expects nested eval to be unsupported.

The other requested gates were not run because the first acceptance fixture is
red. No commit or push was made; the user requested push per green. Existing
untracked collaboration files were left untouched.
