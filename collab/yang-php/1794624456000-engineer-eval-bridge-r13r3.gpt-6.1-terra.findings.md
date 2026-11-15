Created-GMT: 2026-11-14 02:47:36 GMT
Coding-Agent: codex
Session-ID: pending
Role: Engineer

# r13-r3: partial direct-entry coverage; bridge remains incomplete

Added two hand-derived, `:oracle :pending` tests in `eval_emitter_test.clj`.
The deferred-class case checks that a later simple class is visible to shutdown
when a preceding class collision is deferred. The mixed case checks visibility
of a class and function before two case-variant duplicate top-level functions,
and absence of a later class. Each passed independently on all four JVM
interpreters (one test, four assertions per invocation). These invoke eval
entries directly, not through publication, admission, or an eval expression;
they are not PHP oracle observations and do not satisfy the 11-fixture gate.

The two expression lowering paths still refuse eval; there is no `php/eval-run`.
The complete matrix, diagnostic locations, service-backed execution, syntax
and exception controls and all requested suite gates remain outstanding.
In particular `php/warn-record` currently records errno and message only, and
`context` defaults the unit path to `main.php`; diagnostic provenance and the
actual eval location still need to be carried and oracle-checked. No PHP binary
was found locally. The next slice should implement the caller-task request,
correlation, admission and invocation path before claiming any end-to-end row;
verify its location semantics against real PHP rather than infer them from the
existing output strings.

Verification: focused commands used explicit 120-second timeouts and passed.
`cljstyle check test/yang/php/antlr/eval_emitter_test.clj` passed. `clj-kondo`
was not on PATH; its gate did not run. The named existing eval suites,
600-second lower-portable gate, python safepoint additivity, and cross-host
runs were not run. No production code was changed. No commit or push was made:
the requested gates are not green. The two pre-existing untracked prompt/log
files were left untouched.
