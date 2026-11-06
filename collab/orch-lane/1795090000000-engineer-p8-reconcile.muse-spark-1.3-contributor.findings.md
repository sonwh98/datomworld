# Post-P8 reconciliation findings (laptop)

Date: 2026-11-06. Branch: `feat/p8-legacy-retirement`.
Rebase note: `git fetch origin` fails from this laptop
(`Could not read from remote repository`, uid 501 / no network), so no
rebase was performed; the branch otherwise reports up to date and all work
below is on the branch as-is. The seat should rebase onto master before
gating.

## 1. phpt-selection-runs-test — internal.phpt added to the manifest

`Zend/tests/named_params/internal.phpt` left the named-params category and
now lands in the actual fail set. Change
([`phpt_quick_mismatch_manifest.edn`](/Users/sto/workspace/datomworld-p8/test/yang/php/antlr/phpt_quick_mismatch_manifest.edn)):

- `Zend/tests/named_params/internal.phpt` appended to the
  `:named-parameter-binding` gap entries (kept sorted).
- Description added: "Named params on internal functions" (the selection
  entry's `:title`).
- Counts: `:named-parameter-binding` 3 → 4, `:total` 95 → 96.
- Self-consistency verified locally by script: 96 entries = `:total` 96,
  entry set = description-key set.

Engine result derived locally (not `:pending-measured`): running the
selection entry's source through `e2e-harness/run-raw` gives
`{:out "", :diag [], :uncaught {:class "Error", :message "Named arguments
are not supported for this function"}}` — a genuine fail (runs, throws;
not `:refused`, not unsupported), so the `:named-params` family is correct
and the oracle-expect is the `:expect` string already in
[`phpt_selection.edn`](/Users/sto/workspace/datomworld-p8/test/yang/php/antlr/phpt_selection.edn).
Seat to confirm on the shiva pass; the refusal/unsupported counts are
untouched (57), so only the fail-set assertions move.

## 2. packets-match-source-test — stale packet regenerated

The `:string-offset-write` fixture source changed in `bab43c51`
(single-quoted, no-echo, refused → double-quoted `"ab"`/`"c"` + `echo`,
out `"cb"`), but its recorded packet in
[`packets.cljc`](/Users/sto/workspace/datomworld-p8/test/yang/php/antlr/packets.cljc)
was still the old two-statement program. Regenerated from the new source
via the same `cst/parse-source` → `packet->form` path the test uses and
verified `live == recorded` by script (`:packet-match true`).

## 3. portable-fixtures-test string-offset write — diagnosis: stale fixture, NOT a portable gap

The PORTABLE path is fine. `portable-fixtures-test` runs the *recorded*
packet, which was the pre-`bab43c51` program (`$s = 'abc'; $s[0] = 'x';`
with no `echo`), so `""` was the correct output of the wrong program —
the walker (new source) gives `"cb"` while the portable lane replayed the
stale packet. After the item-2 packet regeneration, the portable run of
the recorded packet gives `"cb"` (verified by script). The single prelude
function `php/string-assign` serves both paths; no prelude change needed
and none made. No named gap to report — the divergence was fixture
scoping, and it is closed by the same edit as item 2.

## 4. request-context-fixtures-test:50 — notice scoping + stale fold

Two coordinated changes:

- Engine fix
  ([`prelude.cljc`](/Users/sto/workspace/datomworld-p8/src/cljc/yang/php/antlr/prelude.cljc),
  `php/bi-ob-get-clean`): on an empty buffer it returned `false` *with*
  an errno-8 `ob_get_clean(): Failed to delete buffer` warning. Per the
  php-8.3.35 shiva oracle inside this repo — `:output-buffering` records
  `:diag []` across two `ob_get_clean` calls, the second on an empty
  buffer, while `:output-buffer-flush` records the `ob_end_clean` notice —
  real PHP emits on `ob_end_clean`/`ob_end_flush` but `ob_get_clean` fails
  silently. The warn was removed (bare `false`); `ob_end_clean` and
  `ob_end_flush` still warn. This is the "scope the emission" fix the
  task anticipated, confirmed by the repo's own oracle rather than
  external docs (no PHP/docker on this laptop to re-measure).
- Test fix
  ([`request_context_test.clj`](/Users/sto/workspace/datomworld-p8/test/yang/php/antlr/request_context_test.clj)):
  `normalize-expected-diag` dropped the recorded empty-`ob_end_clean`
  diagnostic back to `[]` (a leftover of the old fold where the engine
  returned bare `false`). The engine now emits the notice per the ruling
  and the fixture records it, so the strip was deleted; the
  headers-already-sent suffix strip stays. The failing assertion was the
  `:output-buffer-flush` fixture (expected `[]` vs engine `[[8 ...]]`);
  `:output-buffering` (the `ob_get_clean` family) stays `[]` on both
  sides after the engine fix.

## Laptop-legal gate (e2e forbidden locally)

- `clojure -M:test -n yang.php.antlr.request-context-test -n
  yang.php.antlr.lower-portable-test -n yang.php.antlr.legacy-corpus-test`:
  **12 tests, 1312 assertions, 0 failures, 0 errors.**
- `e2e_test` not run (forbidden locally; also unloadable here — the
  TypeScript/Python3 generated parser classes are absent and the network
  needed to generate them is down). Its two affected assertions were
  verified by equivalent scripts: packet byte-equality for item 2 and
  engine-throw → fail-set membership for item 1.
- `cljstyle check` on the three touched code files: clean.
  `clojure -M:kondo --lint` on prelude + request-context test: 0 errors,
  0 warnings.

## Seat's shiva gate list

1. Rebase `feat/p8-legacy-retirement` onto `origin/master`
   (laptop fetch failed; unrebased).
2. `e2e_test`: `packets-match-source-test` (regenerated packet),
   `phpt-selection-runs-test` (manifest 96, named-params 4) — full shiva
   run to confirm the fail set is exactly the manifest, incl. the derived
   `internal.phpt` engine result above.
3. `request-context-fixtures-test`, `portable-fixtures-test`,
   `legacy-corpus` namespaces (green on laptop; re-run on shiva).
4. `kondo`/`cljstyle` per repo gate.
