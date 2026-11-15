# Eval bridge r16

The configured JVM composition now lowers both eval forms to a compiler effect,
submits the runtime source to the direct coordinator, links the derived module,
and invokes its installed entry in the caller task with the caller's runtime,
scope, context and hooks. An unconfigured lowering retains the eval refusal.
The harness admits the coordinator's published name and active address. Nested
eval is emitted when the publication registry provides the compiler service.

The linker composition now accepts an explicit positive attempt budget; the
eval harness uses 1024 because a variable-reading eval exhausted the default 64
and repeatedly restarted as pending. JVM machine-payload validation now caches
visited identities within one validation, avoiding repeated traversal of the
shared closure graph on register continuation restore. The hand-built prefix
fixture exercises the dedicated grammar form. The caller-variable fixture uses
single-quoted PHP source so `$x` is read inside eval rather than interpolated by
the caller.

Sequential checks:

- `eval-bridge-test`: 2 tests, 9 assertions, all pass; the two source fixtures
  each run on all four JVM evaluators.
- Eval admission/coordinator/emitter/publication/service/transport/scope-cleanup
  plus machine-data: 29 tests, 155 assertions, all pass. Retention cases are in
  the emitter suite; no separate eval retention test namespace exists.
- Tick fixtures: 1 test, 84 assertions, pass. Include handoff: 1 test,
  3 assertions, pass. The original combined include/tick run timed out at
  240 seconds; the tick namespace also timed out at 180 seconds, so the two
  relevant tests were rerun independently, with 240/600-second limits.
- Lower portable: 8 tests, 1254 assertions, pass under 600 seconds.
- clj-kondo on edited Clojure files: 0 errors, 0 warnings; cljstyle check on
  those files and `git diff --check` clean.
- A focused AST-walker nested eval returned `42`; it was not exercised across
  all backends as a separate fixture.
