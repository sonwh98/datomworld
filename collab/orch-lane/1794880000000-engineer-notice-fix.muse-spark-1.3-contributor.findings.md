# Findings: the ruled empty-buffer notice (engineer notice-fix)

Task: `/tmp/muse-notice-fix.md` — architect verdict 2(a): preserve the recorded
notice; the no-buffer arm must emit `php/warn` errno 8 with the recorded message,
then return `false`.

## Changes

1. `src/cljc/yang/php/antlr/prelude.cljc` (ob_* section, ~lines 390-403)
   - `php/bi-ob-end-clean`: no-buffer arm now does
     `(php/warn 8 "ob_end_clean(): Failed to delete buffer. No buffer to delete")`
     and returns `false` (was bare `false`).
   - `php/bi-ob-get-clean` / `php/bi-ob-end-flush`: restructured to check the
     buffer depth directly instead of delegating to `php/bi-ob-get-contents`
     (which returns `false` silently, per PHP 8.3 — `ob_get_contents()` on no
     buffer emits no warning). Each emits its own `php/warn` errno 8 and
     returns `false`:
     - `ob_get_clean(): Failed to delete buffer. No buffer to delete`
     - `ob_end_flush(): Failed to delete and flush buffer. No buffer to delete or flush`
       (matches the oracle text already recorded in
       `test/yang/php/antlr/phpt_selection.edn` for bug64960).
   - `ob_get_contents()` no-buffer arm intentionally left silent (returns
     `false`, no warning) — matches PHP 8.3 semantics.
2. `test/yang/php/antlr/programs_p5.cljc` — `:output-buffer-flush` fixture gains
   `:diag [[8 "ob_end_clean(): Failed to delete buffer. No buffer to delete"]]`.
   This fixture IS the regression: `request-context-test` asserts the engine
   diag vector equals the fixture diag exactly.
3. Test-side suppression: none found — neither `request_context_test.clj` nor
   `e2e_harness.clj` filters, masks, or drops errno-8 diags. Nothing to remove.

## Verification (all observed this session)

- `clojure -M:test -n yang.php.antlr.request-context-test` → 3 tests,
  35 assertions, 0 failures, 0 errors. (First attempt failed with
  `ClassNotFoundException yang.php.antlr.gen.PhpLexer` because the generated
  parser classes were absent; `clojure -M:antlr-gen` regenerated the three java
  files — its trailing network step failed with `UnresolvedAddressException`
  in this sandbox, but the classes under `build/antlr/php/classes` were
  present/complete afterward and the test run passed.)
- Only `request_context_test.clj` consumes `programs-p5`; no other test
  namespace touches `ob_*` (besides the `phpt_selection.edn` oracle data,
  which needs no suite run per the gate).
- `clojure -M:kondo --lint` on both changed files → errors: 0, warnings: 0.
- `cljstyle check` on both changed files → exit 0.
- Note: a direct `-e` probe of the fixture source failed to load for an
  unrelated reason (`ClassNotFoundException
  yang.python.antlr.gen.Python3Lexer` — the python3 generated parser is absent
  in this checkout and `-e` pulls a wider classpath). The fixture test itself
  asserts `(= (vec diag) (diagnostics r))`, so its pass with the new `:diag`
  entry proves the engine emits exactly errno 8 + the recorded message; the
  `false` return is proven by the unchanged `:out "abbool(false)\n"`.

## Commit

- On `feat/p8-legacy-retirement`, message
  `fix(yang.php): the ruled empty-buffer notice — php/warn errno 8 + false`,
  no `Co-Authored-By`, not pushed. This findings file left uncommitted.
