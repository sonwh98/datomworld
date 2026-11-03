# P7-a guest execution budget findings

## Delivered

Commit `483ae0bc` (`feat(yang.php): the optional safepoint-count execution budget`) on `feat/p7a-exec-budget`; not pushed.

- Added the bundled `phpsp` profile and optional per-run safepoint counter. Only existing PHP `:loop` and `:call` sites are charged; omission leaves the original AST and execution unchanged.
- `:execution-budget` is available through the JVM `run-php` / `run-vms` harness.
- Exhaustion unwinds past guest handlers, produces exactly `PHP Fatal error:  Maximum execution time of 30 seconds exceeded`, and bypasses uncaught-exception handling, shutdown functions, and destructors.
- Extended shared safepoint insertion with an opt-in `:sequence` profile entry so PHP call-application sites can run an entry hook; existing profiles retain their previous behavior.
- Documented the deterministic, non-wall-clock contract in `docs/design/yang.php.md` section 12.

## Verification

- Focused `yang.php.antlr.safepoint-test`: 2 tests, 9 assertions, 0 failures/errors. Covers exact fatal text on AST walker, semantic, stack, and register VMs; no guest catch/destructor output; default unbounded behavior; unexhausted-budget transparency.
- `yang.safepoint-test`, `yang.php.antlr.class-model-test`, `yang.php.antlr.objects-test`, `yang.php.antlr.generators-test`: 22 tests, 1,218 assertions, 0 failures/errors.
- `clj -X:fmt`: completed after trusting this checkout's `mise.toml`; unrelated formatter/generated-manifest changes were restored.
- Focused clj-kondo over every changed Clojure source/test: 0 errors, 0 warnings.
- `git diff --check`: clean before commit.

Generated parser classes were required for the standard PHP harness and were produced with `clj -M:antlr-gen`; generated files remain ignored/uncommitted. The findings file is intentionally uncommitted.
