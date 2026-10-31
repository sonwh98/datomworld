Completed-GMT: 2026-09-29 19:28:04 GMT
Completed-Local: 2026-09-30 02:28:04 +07
Coding-Agent: claude
Session-ID: 10c592b5-4997-47d1-8055-8010792bb808

# Report r4: host-fn rendering, CLJD compile error and key-order fix

All checks the brief asked for pass: CLJD 2242 tests, 0 failures; kondo clean; focused JVM clean. Every check in this round ran in the foreground.

report-r3.md was never written because the r3 turn ended before verification. This report also covers the r3 change (the key-order fix).

Nothing is staged or committed. The diff touches only `src/cljc/yin/repl.cljc`, `test/yin/repl_test.cljc` and `test/yin/repl/serve_test.cljc`. `yin.vm/*`, `dao.data` and `dao.pretty` are unchanged.

## r3: CLJD key order (cause and fix)

**Cause.** On ClojureDart, `dao.pretty/pp-str` prints a map's entries in the map's own seq order, and CLJD's small maps don't keep insertion order. So `{:type :host-fn}` with `:name` added printed as `{:name '+, :type :host-fn}`.

The same hazard applied to every `:type`-tagged map the REPL prints, including `{:type :closure ...}`. The first-round test only checked the closure prefix, and the CLJD lane never got that far.

**Fix (rendering only).** In the Rendering section of `src/cljc/yin/repl.cljc`:
- New `typed-map` builds a `sorted-map-by` with `typed-key-compare`: `:type` first, then the remaining keys ordered by `pr-str`.
- `host-fn-marker` now builds its marker through `typed-map`.
- In `quote-symbols`, a map that has a `:type` entry is rebuilt with `typed-map`. Untyped maps still go through `(into {} ...)`.
- The `:type` check scans entries with `(some #(= :type (key %)) x)` rather than `contains?`, because `contains?` throws on a sorted map whose keys don't compare with a keyword.

**Visible change on CLJ.** A closure now prints as `{:type :closure, :entry 3, :params ['i], :segment -1}`. Before, it was `{:type :closure, :params ['i], :entry 3, ...}` (insertion order). No existing test pinned the old order. This rendering-only change is what makes the output the same on every host.

## r4: CLJD compile error

- **Cause:** the new test used `(array-map :z 2 :type :x)`, and ClojureDart has no `array-map`.
- **Fix:** replaced it with the literal `{:z 2 :type :x}`. On CLJ a small literal map keeps insertion order, so `:type` still iterates second and the test still catches the bug (M4 below).
- **Other JVM-only constructs:** I grepped the added lines for `array-map`, `java.`, `clojure.lang`, `Throwable`, `#'`, `with-out-str`, `StringWriter`, `atom` and similar. There were no other hits.

**Second CLJD issue: line layout.** The first CLJD run after the compile fix got the key order right. It then failed on layout:
- CLJD's `dao.pretty` wraps a map whose inline form is over 60 characters, and puts each entry on a new line without commas.
- CLJ/CLJS use `pprint`, which wraps at 72.
- My 62-character closure fixture fell between the two budgets.

`dao.pretty` is outside this task's allowed files, so I shortened the fixture to `{:type :closure, :params ['i], :entry 3, :segment -1}` (52 characters, one line on every host), with a comment. The ordering check is not weaker: insertion order `:params :entry :segment` still differs from the rendered `:entry :params :segment`.

## Tests (current)

`test/yin/repl_test.cljc`:
- `a-host-function-renders-as-a-named-portable-marker`: runs on all 4 VMs. Covers `+`, `dao.space.query/q` after require, host fns in a collection built at runtime, `==` rendering as `=`, a closure prefix, and no `#object[`.
- `a-typed-value-renders-in-one-key-order-on-every-host`:
  - a closure-shaped map renders `:type` first, then the other keys in printed order, on one line
  - a typed map nested in an untyped map is ordered too
  - `(sorted-map 1 2)` still renders
- `a-host-function-without-a-known-name-renders-nameless`: nameless markers.

`test/yin/repl/serve_test.cljc`:
- `a-served-host-function-renders-as-the-local-marker`: served answers equal the local texts, and none contains `#object[`.

## Mutation proof (this round)

M4: route typed maps through `(into {} ...)` instead of `typed-map`. On CLJ this gives 2 failures:
- `"{:type :closure, :params ['i], :entry 3, :segment -1}"`
- `"{:b {:z 2, :type :x}}"`

Reverted from a backup copy afterwards; `git diff --stat` is back to the 3 files. M1 to M3 from r1 still apply to the marker and naming paths.

The host-fn marker ordering can only fail on CLJD, because CLJ and CLJS keep insertion order for a 2-key map. The CLJD lane's failure before this fix and its pass after it are the proof for that.

## Verification (all in the foreground)

| Check | Result |
|---|---|
| kondo (`clj -M:kondo --lint` on the 3 files) | 0 errors, 0 warnings |
| Focused JVM (`clj -M:test -n yin.repl-test -n yin.repl.serve-test`) | 55 tests, 277 assertions, 0 failures, 0 errors |
| `bb build:yin-repl-peer` | exit 0; `build/yin-repl-peer` regenerated at 02:15 |
| `bb test:cljd`, run 1 (after the array-map fix) | exit 1; 2241 passed, 1 failed: the layout issue above. Log `target/r4-cljd.log` |
| `bb test:cljd`, run 2 (after the fixture fix) | exit 0, "All tests passed!", 2242 tests. Log `target/r4-cljd-2.log` |

- **New tests on CLJD:** the reporter throttles its progress lines, so not every test name appears in the log. The compiled `test/cljd-out/yin/repl-test_test.dart` and `test/cljd-out/yin/repl/serve-test_test.dart` contain all four new tests, and the total matches run 1's 2241 + 1.
- **Peer binary:** it was built before the fixture change. That change is test-only, so the binary is current for the source in this diff.
- **Full JVM and CLJS:** not run this round; the brief says the orchestrator runs them. The last full results I have predate the r3/r4 changes: JVM 2374/184439/0 and CLJS 2279/50915/0 (r1).
- **cljstyle:** not run; it was permission-blocked in r1.

## Residual notes for the orchestrator

- **Layout still differs between hosts** for rendered values whose inline form is over 60 characters:
  - `dao.pretty` has a 60-character budget on CLJD versus pprint's 72 on CLJ/CLJS, and wraps map entries on CLJD without commas.
  - Short values, including every marker, print the same on all hosts.
  - Making long values identical needs a `dao.pretty` change, outside this task's files.
- **Untyped maps with two or more entries** still print in host iteration order on CLJD; that behaviour predates this change. The ordering rule covers only `:type`-tagged maps.
- **`typed-map` orders keys by `pr-str`.** Two distinct keys with the same printed form inside one `:type` map would collapse into one entry. VM-produced typed maps use keyword keys, so this can't happen for them.
