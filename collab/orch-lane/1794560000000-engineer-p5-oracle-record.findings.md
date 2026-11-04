# SEAT-RUN instructions: P5 oracle recording on shiva

## What landed (uncommitted on `feat/p5-oracle-recording`)

`src/dev/yang/php/oracle.clj` extended for programs-P5:

- `p5-prologue` builds the `auto_prepend_file` prologue assigning
  `$_GET`/`$_POST`/`$_COOKIE`/`$_SERVER`/`$_ENV`/`$_FILES`/`$_REQUEST`
  from the fixture `:request` record, mirroring `php/bind-request!`
  (`src/cljc/yang/php/antlr/prelude.cljc`, section `request and output`):
  `$_GET` = query, `$_POST` = body, `$_COOKIE` = cookies,
  `$_SERVER` = server fields with `REQUEST_METHOD`/`REQUEST_URI`
  appended, `$_ENV` = env, `$_FILES` = files,
  `$_REQUEST = array_merge($_GET, $_POST)` (POST wins).
- `run-php-file-with-prepend`, `check-p5-fixture`, `run-p5`,
  `print-fold` (P4 `pin` shape per fixture).
- `-main` modes: `--p5` (run only P5 fixtures), `--print-prologue [id]`
  (dry run, no docker).

Verified here (no docker): `--print-prologue` output for
`:superglobals-from-request` hand-checked against the engine's expected
superglobal values — concatenating the prologue's values in the
fixture source's echo order yields exactly the fixture `:out`
(`12abcPOST/hello?a=1fixture.testtesttestfixture12post`).
`clojure -M:test -n yang.php.antlr.request-context-test` green
(3 tests, 35 assertions). kondo/cljstyle binaries absent on this
machine — run them on shiva before folding.

NOTE: `p5/fixtures` holds **8** request-context fixtures, not 9
(superglobals-from-request, output-buffering, output-buffer-flush,
headers-cookies-status, headers-after-output, error-handler-and-warnings,
exit-runs-shutdown, exit-string). If the seat expects a 9th, say which.

## Run on shiva

```sh
cd /path/to/datomworld-p5r   # feat/p5-oracle-recording
git pull                      # after this commit is pushed, or copy the tree
timeout 300 clojure -M:dev:test -m yang.php.oracle --p5
```

Uses `docker run --rm php:8.3-cli` per fixture (same flags as the
existing runner, plus `-d auto_prepend_file=/w/<id>-pre.php`).
Needs docker + `php:8.3-cli`; `timeout 300` covers the whole pass
(each `docker run` inherits no per-run timeout — same as existing
runner; add `timeout 300 docker ...` per run if shiva is slow).

## What to paste back for the fold

The full stdout, in particular every `FOLD <id> {...}` line:

```
FOLD <id> {:out "..." :diag [...] :uncaught ... :oracle "<banner>"}
```

Fold rule (P4 `pin` shape): recorded values override hand-authored
pins; on the implementor side, paste each `FOLD` map into the
`recorded` map keyed by id and re-run
`yang.php.antlr.request-context-test`. A fixture whose recorded
`:out`/`:diag`/`:uncaught` differs from the hand-derived pin is a
real discrepancy — report it, do not silently keep the pin.
