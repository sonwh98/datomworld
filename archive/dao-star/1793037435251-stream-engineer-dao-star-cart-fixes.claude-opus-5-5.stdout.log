Completed-GMT: 2026-10-27 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-27, +07 (clock not read)

All seven findings are fixed and the demo tests pass. I did not write the findings report to `collab/`; the report is below.

## Checks
- `clojure -M:test` on the five demo namespaces (`catalog-test`, `view-test`, `edge-test`, `app-test`, new `fixes-test`): 36 tests, 524 assertions, 0 failures, 0 errors. I ran this before the final two kondo-only edits (an unused import in `fixes_test.clj` and a kondo suppression comment in `store.clj`) and did not re-run the tests afterward.
- `clj -M:kondo --lint` on the demo files plus `jvm.clj`: clean after those two final edits.
- I changed the code before writing the tests, and the new tests passed on their first run. I did not confirm they fail against the old code, so this was not strict test-first.
- I touched no files outside the demo's ownership.

## What changed
1. **Symlink escape (`edge.clj`):** `static-file` now resolves the real path of both the root and the target and requires the real target to sit under the real root. A missing path answers nil. The test refuses an intermediate-directory symlink and a symlinked file, and still serves a plain file.
2. **Tagory tempids (`catalog.clj`):** tempids come from a name-to-position map, not `hash`. The test uses `"Aa"` and `"BB"`, which hash alike. Both stay distinct after a save and reload, and removing one leaves the other.
3. **Reconnect (`view.cljc`):** both begin pages now subscribe with `@get('…/s/ROOT',{retry:'always'})`. Tests assert the rendered attribute. The loopback reconnect test is unchanged and still passes.
4. **Snapshot race (`store.clj`):** the update and its snapshot are one critical section under a lock held on the store. The test makes the first write slow and starts a second save behind it. The reopened file carries both edits.
5. **Image URLs (`grid_effects.clj`):** `check-image` decodes the URL once and stores the canonical decoded path, which `enc-path` encodes once for the page. An encoded spelling of an existing filename (`/images/P-141%20%20Green%20Camouflage.png`) is stored decoded and served. A second add of either spelling is rejected as a duplicate, and a malformed `%zz` is rejected.
6. **Line terminators (`view.cljc`):** `js-lit` escapes CR, LF, U+2028 and U+2029. `esc` now emits CR and LF as `&#13;` and `&#10;`, so a textarea keeps its newlines. `sse-elements` folds any CR or LF. Tests cover LF, CRLF and bare CR through the page, the signals JSON, the transact, the written file and the grid projection.
7. **Default catalog path (`main.clj`, `store.clj`):** I chose the default. With `CATALOG_PATH` unset, saves go to `target/cart-catalog.edn`. `write-atomically!` now creates missing parent directories. `store/open` logs "MEMORY ONLY" loudly when it has no path at all, and the demo README says so.

## Concern
- Bare `\r` is also folded to a space by `sse-elements` as a safety net, but no current projection sends one. It can only come from future raw HTML.
