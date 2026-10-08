Created-GMT: 2026-09-15 22:46:40 GMT
Created-Local: 2026-09-16 05:46:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Findings: ast->semantic-bytecode r5 (not-empty carrier bug; deferred-set wording)
Role: VM Runtime
Implementer: claude-opus-5 | Status: complete (uncommitted working-tree diff)

## Fix 1: `not-empty` discarded a metadata-bearing empty map

**Reproduction (r4 code).** I added the regression test first and ran
`clojure -M:test -n yin.vm-test`. The input is the exact reproduction,
`{:type :literal :value (with-meta [] (with-meta {} {:meaning 1}))}`:
```
FAIL in (semantic-bytecode-strips-reader-positions-inside-metadata) (v2_test.cljc:454)
an empty metadata map that carries metadata is kept
expected: (= {:meaning 1} (meta (meta v)))
  actual: (not (= {:meaning 1} nil))

FAIL in (semantic-bytecode-strips-reader-positions-inside-metadata) (v2_test.cljc:455)
an empty metadata map that carries metadata is kept
expected: (= {:meaning 1} (meta (meta back)))
  actual: (not (= {:meaning 1} nil))

Ran 23 tests containing 172 assertions.
2 failures, 0 errors.
```
The value's metadata is an empty map that carries `{:meaning 1}`. Plain
`not-empty` looks only at the map's entries, so it collapsed the map to
nil and `{:meaning 1}` was lost. The loss showed up both in the row
(`v`) and after the full round trip (`back`).

**Diff (`src/cljc/yin/vm.cljc:631-636`, end of `strip-reader-positions`):**
```diff
     (if m
       ;; metadata is itself a value: its entries and its own metadata may
       ;; carry reader positions too
-      (with-meta x' (not-empty (strip-reader-positions
-                                 (dissoc m :line :column :end-line :end-column))))
+      (let [m' (strip-reader-positions
+                 (dissoc m :line :column :end-line :end-column))]
+        ;; not (not-empty m'): an entry-less map may still carry metadata
+        (with-meta x' (when-not (and (empty? m') (nil? (meta m'))) m')))
       x')))
```
`m'` has already been through `strip-reader-positions`, so its own metadata
has been stripped before the check. A metadata map is collapsed to nil only
when it has no entries and also carries no metadata after that stripping.
So `(with-meta {} {:line 3})` still collapses: its stripped metadata is nil
and it has no entries.

Address impact: none. `dao.jing` already drops empty collection metadata
when hashing (`(if (seq m) ...)` in `order-normalize`), so this fix only
stops the value from losing data. Both the r4 code and this code mint the
same address for the input.

**Test (`test/yin/vm_test.cljc:449-455`):** a new `testing` block in
`semantic-bytecode-strips-reader-positions-inside-metadata`. It asserts
that `(meta (meta value))` is `{:meaning 1}` both in the projected row slot
and in the value reconstructed by `semantic-bytecode->ast`.

## Fix 2: deferred custom-comparator set disclosure

There is no code change. The `same-meta?` docstring
(`src/cljc/yin/vm.cljc:646-653`) no longer implies the case fails
closed:
```diff
    admits several `=`-equal, metadata-distinct elements is out of scope, the
    same class of pathological-input residual `dao.jing.md` documents for its
-   own encoder (pathological symbols, scalar metadata)."
+   own encoder (pathological symbols, scalar metadata). That deferred case is
+   silent and order-dependent, not reliably fail-closed: which `=`-equal
+   element the lookup picks depends on traversal order, so a metadata
+   mismatch may throw or may be silently accepted."
```
No test was added, matching the deferral.

## Results after the fix

JVM, `clojure -M:test -n yin.vm-test`:
```
Testing yin.vm-test

Ran 23 tests containing 172 assertions.
0 failures, 0 errors.
```
The test count is unchanged from r4 because the regression is a new
`testing` block inside an existing deftest. It adds 2 assertions.

**Not re-run this round:**
- **cljs:** the contract asks only for the JVM run. In r4 the cljs build
  was clean for `yin.vm-test`.
- **`bb test:cljd`:** r4's run was blocked at compile time by the
  untracked `test/bench/yin_vm_bench.cljc`
  (`Unknown symbol: measure-ms`, line 113). I have no evidence that
  blocker has been cleared, so cljd verification of this unit is still
  outstanding.

## Boundaries

- Edited only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`,
  plus this file.
- Nothing staged or committed.
