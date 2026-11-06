# P8 final items — engineer findings (muse-spark-1.3-contributor)

Task: `/tmp/muse-p8-fixes.md` (provenance + four P9 contract residuals).
Branch: `feat/p8-legacy-retirement`.

Note: the landing-review log named in the task
(`collab/orch-lane/1794900000000-architect-p8-final-confirm...stdout.log`)
is not present in this checkout (no `*arch*` under `/tmp`, no such entry
under `collab/orch-lane/`), and `~/ci` does not exist on this laptop.
All architect citations below are taken from the task file itself, which
quotes them with file:line precision; the cited lines (prelude_p9.cljc:673
`php/json-encode-fail`, prelude_p9.cljc:808 `php/json-number-end`,
builtins.cljc:171 EXTR constants) match the tree exactly.

## 1. Provenance (`:measured-at` per slow-gap record)

`test/yang/php/antlr/phpt_quick_mismatch_manifest.edn`: all 6
`:slow-known-gaps` records now carry a one-line `:measured-at` map
`{:tree ... :host "shiva" :date ... :log ...}` (EDN-validated: 6 gaps,
6 measured):

- `bug40809` → `{:tree "dbe9e072" :date "2026-11-05"
  :log "~/ci/rm-40809-long.log"}` (the 90-min remeasure).
- The other five (`gc_013`, `gc_023`, `bug39438`, `bug64660`, `gc_012`) →
  `{:tree "06cd429f" :date "2026-11-04" :log "~/ci/rm-phpt-quick.log"}`.
- `bug44069` / `bug63055` have no records to annotate: both were removed
  after they passed on the measured trees (`514fae8d`, `c65fb4b1`).

## 2. P9 contract residuals

(a) `array_unique` default is `SORT_STRING` (`prelude_p9.cljc`,
`php/bi-array-unique`): default flag `0` → `2`. The old test pinned the
loose-equality behavior (`array_unique(['1.0','1'])` → `"1.0"`); it now
expects `"1.0,1"`, with explicit `SORT_STRING` / `SORT_NUMERIC` /
`SORT_REGULAR` pins alongside.

(b) `json_encode` failure path (`php/json-encode-fail`): non-throw mode
now answers `false` with no warning (the `php/warn` call is removed);
throw mode unchanged. Depth: a container now fails when
`(>= (+ depth 1) maxdepth)` — the top-level container is depth level 1,
so `json_encode([1], 0, 1)` fails, consistent with the decode side
(`json_decode('[1]', true, 1)` → NULL, already covered). Boundary note:
at the default depth 512 this allows 511 nesting levels rather than 512;
no test covers that extreme and no PHP binary exists on this laptop to
arbitrate it — flagged, not verified.

(c) JSON scanner (`php/json-number-end` + `php/json-number`): the scan
now admits `+` (43) so `1e+2` / `1E+2` are single tokens; validation
requires a whole number (`php/full-num?`, which also rejects truncated
forms like `1e`, `1e+`, `1+` that the old `:php/null` check accepted as
`:leading` prefixes) and rejects leading-`+` / leading-zero tokens via
the new `php/json-lead-bad?` (`+1`, `01`, `-01` → NULL).

(d) `EXTR_PREFIX_ONLY_IF_EXISTS` (`builtins.cljc:171`): constant removed
from `builtins/constants` (so `defined()` is false and a bare read is the
standard lowering refusal, not a fold); `php/bi-extract` refuses mode 7
with `ValueError` (`> flags 6`); the dead mode-7 branches in
`php/extract-needs-prefix?` / `php/extract-target` are removed.

## Gate

- Focused regressions were added first and observed failing (8 failures,
  each the architect's counterexample), then fixed: `builtin-surfaces-test`
  167 assertions, 0 failures.
- `clojure -M:test -n yang.php.antlr.builtin-surfaces-test -n yang.php.antlr.request-context-test` → 6 tests, 202 assertions, 0 failures, 0 errors.
- `clojure -M:kondo --lint` on the three changed sources → 0 errors, 0 warnings.
- `cljstyle`: no binary, alias, or repo task (`deps.edn`/`bb.edn`/`bin/`)
  on this laptop — not run; touched lines follow the surrounding style.
- Committed on `feat/p8-legacy-retirement` as
  `fix(yang.php): the landing-review items — provenance, array-unique
  default, json failure/depth/scanner, the extract constant`, no
  Co-Authored-By, not pushed. This findings file left uncommitted per the
  task.
