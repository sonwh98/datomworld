# Frontier r2 — Python → PHP finally replacement

The replacement fixture initialized `$replacement` before PHP's `run-main`
released globals. The post-unit observation then captured an unset value, and
`finally` threw that value rather than the PHP exception the fixture intended.
The pending Python foreign condition is superseded by the PHP-created exception;
the replacement case does not rethrow the Python wrapper.

The PHP fixture now provides `seed_replacement`, invoked only for the
Python→PHP `:finally-replacement` observation before `replacement_payload` is
captured and `probe` executes. The existing assertion compares the escaping
payload to that captured object, alongside transfer count, unreachable-after,
and reason checks. No pair-matrix rows were edited. No provenance carrier mint,
retention, VM primitive, or validator changed: foreign reception and retention
of its complete condition remain untouched, and no shape is used as evidence.

The user relayed the architect's ruling. The specified ruling file could not be
read in this worktree: its local `origin/master` predates it, and `git fetch`
failed because the worktree's Git metadata is read-only. The staged order above
follows the ruling as quoted in the dispatch.

## Execution evidence

- `target/frontier-r2/focused.log`: targeted leg, 1 test, 4 assertions, zero
  failures or errors; exit 0. The test checks the backend set and the full
  expected observation independently for each backend.
- `target/frontier-r2/python-php-pair.log`: 8 Python→PHP scenarios × 3
  backends, 24 assertions, zero failures or errors; exit 0. The target leg
  printed the same result for register, semantic, and stack:
  `{:transferred true :once true :after false :reason :yang.call/thrown
  :payload-identical true}`. All other seven scenarios printed green.
- `target/frontier-r2/source-compile.log`: directed source compilation,
  109 assertions, zero failures or errors; exit 0.
- `target/frontier-r2/php-surface.log`: PHP provenance surface checks,
  3 tests, 14 assertions, zero failures or errors; exit 0.
- `target/frontier-r2/kondo.log`: zero errors and zero warnings;
  `target/frontier-r2/diff-check.log`: `git diff --check` exit 0.
- Generated parser classes were already present from r1's digest-checked
  `gen:antlr` run; regeneration was not needed.
- `target/frontier-r2/php-fixtures.log`: shared-task PHP fixture batch,
  53 assertions, zero failures or errors; exit 0. Every PHP fixture was
  checked on register, semantic, and stack, including the previously flipped
  payload-identity and retained-condition rows.

The requested local focused acceptance is green. The steward's independent
acceptance run remains for the seat. No commit was made.
