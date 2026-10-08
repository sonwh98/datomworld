Created-GMT: 2026-09-15 22:30:00 GMT
Created-Local: 2026-09-16 05:30:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Findings: ast->semantic-bytecode r4 (metadata inside metadata; nodes vector metadata)
Role: VM Runtime
Implementer: claude-opus-5 | Status: complete (uncommitted working-tree diff)

## Method

I added the reproduction tests first, following gpt-6-astra's r3 report,
and ran them against the r3 code with
`clojure -M:test -n yin.vm-test`:
```
Ran 23 tests containing 170 assertions.
7 failures, 0 errors.
```
All 7 failures are the new assertions, and every one of them fails
because of one of the three defects below. The earlier tests still
passed. After the fixes the same command gives 0 failures.

The shared root cause of Fixes 1 and 2: both functions walked a value's
structure but treated its metadata as opaque.

## Fix 1: `same-meta?` compares metadata recursively

**Reproduction (r3 code):** the reviewer's program, built through the real
frontend:
`(yang/compile (list 'if true ^{:note ^{:meaning 1} x} [] ^{:note ^{:meaning 2} x} []))`.
Vector literals compile to `{:type :literal :value form}` with the form's
metadata intact (`yang/clojure.cljc:71`, `:146`), so this is ordinary
reader and frontend output.
```
FAIL in (semantic-bytecode-compares-metadata-inside-metadata) (v2_test.cljc:425)
(if true ^{:note ^{:meaning 1} x} [] ^{:note ^{:meaning 2} x} [])
              is a loud collision, not a silent merge
expected: (= "Semantic bytecode address collision" (error-message ...))
  actual: (not (= "Semantic bytecode address collision" nil))
```
The projection did not throw: both branches silently shared one row.

**Diff (`src/cljc/yin/vm.cljc:638-651`):**
```diff
   "True when the `=` values `a` and `b` also carry `=` metadata at every
-   depth. `=` alone ignores metadata.
-
-   Scope boundary: metadata-on-metadata is not compared recursively. A value
-   whose metadata map itself holds values carrying semantically distinct
-   nested metadata is out of scope for the collision guard. The set branch
-   assumes ordinary set semantics, at most one element per `=`-equivalence
-   class; a set built with a comparator that admits several `=`-equal,
-   metadata-distinct elements is out of scope. Both are the same class of
-   pathological-input residual `dao.jing.md` documents for its own encoder
-   (pathological symbols, scalar metadata)."
+   depth, including inside metadata itself (a metadata value, or a metadata
+   map's own metadata, compares through `same-meta?` too). `=` alone ignores
+   metadata.
+
+   Scope boundary: the set branch assumes ordinary set semantics, at most one
+   element per `=`-equivalence class; a set built with a comparator that
+   admits several `=`-equal, metadata-distinct elements is out of scope, the
+   same class of pathological-input residual `dao.jing.md` documents for its
+   own encoder (pathological symbols, scalar metadata)."
   [a b]
   (and (= (meta a) (meta b))
+       (or (nil? (meta a)) (same-meta? (meta a) (meta b)))
        (cond (map? a) ...
```
The metadata map is passed back through `same-meta?`. Its map branch then
recurses into each metadata value, and the call's own first clause
compares the metadata map's own metadata, at any depth. The `nil?` guard
ends the recursion. The r3 disclosure that wrongly grouped nested
metadata with the custom-comparator case is removed. Only the
custom-comparator set disclosure (Finding 4, still deferred) remains.

**Tests:** `semantic-bytecode-compares-metadata-inside-metadata`
(`v2_test.cljc:419`) checks that:
- distinct nested metadata now throws
  `"Semantic bytecode address collision"`;
- equal nested metadata (`{:meaning 1}` on both branches) still shares one
  row, and reconstruction returns `{:meaning 1}` at `:note`'s metadata on
  both occurrences.

The collision throw is still the right outcome, not a merge. `dao.jing`
does not hash symbol metadata, so the two branches genuinely share an
address; see the r2 limitation in `ast->semantic-bytecode`'s docstring.

## Fix 2: `strip-reader-positions` recurses into metadata

**Reproductions (r3 code):**
```
FAIL in (semantic-bytecode-strips-reader-positions-inside-metadata) (v2_test.cljc:443)
positions on a metadata value are stripped; the note is kept
expected: (nil? (meta (:note (meta v))))
  actual: (not (nil? {:line 1, :column 9}))

FAIL in (semantic-bytecode-strips-reader-positions-inside-metadata) (v2_test.cljc:448)
positions on the metadata map's own metadata are stripped
expected: (= {:k 1} (meta (meta v)))
  actual: (not (= {:k 1} {:line 3, :k 1}))

FAIL in (semantic-bytecode-strips-reader-generated-positions-in-metadata) (v2_test.cljc:462)
expected: (nil? (meta (:note (meta row-value))))
  actual: (not (nil? {:line 1, :column 9}))

FAIL in (semantic-bytecode-strips-reader-generated-positions-in-metadata) (v2_test.cljc:463)
expected: (nil? (meta (:note (meta back))))
  actual: (not (nil? {:line 1, :column 9}))
```
The last two use the reviewer's exact reader case. `"^{:note (helper x)} []"`
is read with `clojure.lang.LineNumberingPushbackReader`, and the test first
asserts that the reader really attaches `{:line 1 :column 9}` to the list
held in the metadata. The form then goes through `yang/compile`, the
projection, and reconstruction. The positions survived in both the row
and the rebuilt map.

**Diff (`src/cljc/yin/vm.cljc:609-634`):**
```diff
    `:line`/`:column`/`:end-line`/`:end-column` from metadata at every depth
-   of a `data`/`key` payload, keeping all other metadata and every collection
+   of a `data`/`key` payload, including inside metadata maps and their own
+   metadata, keeping all other metadata and every collection
 ...
     (if m
-      (with-meta x' (not-empty (dissoc m :line :column :end-line :end-column)))
+      ;; metadata is itself a value: its entries and its own metadata may
+      ;; carry reader positions too
+      (with-meta x' (not-empty (strip-reader-positions
+                                 (dissoc m :line :column :end-line :end-column))))
       x')))
```
The position keys are dissoc'd from the metadata map, and the result is
passed back through `strip-reader-positions`. Its map branch recurses into
every metadata value, and its own `m` step strips the metadata map's own
metadata. This terminates, because metadata nesting is finite.

**Tests:**
- `semantic-bytecode-strips-reader-positions-inside-metadata`
  (`v2_test.cljc:435`), which runs on all hosts:
  - a positioned `(helper x)` metadata value loses its positions but keeps
    the value;
  - a metadata map carrying `{:line 3 :k 1}` keeps exactly `{:k 1}`.
- `semantic-bytecode-strips-reader-generated-positions-in-metadata`
  (`v2_test.cljc:451-465`) is the real-reader case. It is JVM-only because
  it uses `LineNumberingPushbackReader`, and is wrapped in
  `#?(:cljd nil :clj ...)` with `:cljd` first: cljd's host-eval pass reads
  `:clj` branches, so a bare `#?(:clj ...)` would leak into the cljd build.

## Fix 3: `:nodes` vector metadata is refused on reconstruction

**Reproductions (r3 code):** correctly hashed rows (via
`dao.jing/segment-key`, which hashes vector metadata):
```
FAIL in (semantic-bytecode-nodes-vector-metadata-is-refused) (v2_test.cljc:470)
expected: (= :slot-kind (error-rule ... [:application op-id (with-meta [] {:purpose 1}) false] ...))
  actual: (not (= :slot-kind nil))

FAIL in (semantic-bytecode-nodes-vector-metadata-is-refused) (v2_test.cljc:474)
expected: (= :slot-kind (error-rule ... [:dao.stream.apply/call :op/add (with-meta [op-id] {:purpose 1})] ...))
  actual: (not (= :slot-kind nil))
```
Both rows reconstructed without error. `mapv build` would then drop the
vector metadata, so re-projection would mint a different address.

**Diff (`src/cljc/yin/vm.cljc:736-740`, `child` in `semantic-bytecode->ast`):**
```diff
-           :nodes (if (vector? v)
+           ;; as for :syms: projection mints a metadata-free vector
+           :nodes (if (and (vector? v) (nil? (meta v)))
                     (mapv build v)
                     (defect :slot-kind
-                            "Semantic bytecode nodes slot is not a vector"
+                            "Semantic bytecode nodes slot is not a metadata-free vector"
```

**Tests:** `semantic-bytecode-nodes-vector-metadata-is-refused`
(`v2_test.cljc:467`) covers both `:nodes` tags, `:application` and
`:dao.stream.apply/call`.

## Still deferred

Finding 4, the custom-comparator set, is still out of scope. It is
disclosed in `same-meta?`'s docstring, as quoted in the Fix 1 diff. No
test was added. The broader §7.4 validator items remain separate work:
`:root-reachable`, `:variable-scope`, and `plain-data?` checks on
`data`/`key` slots.

## Results after the fixes

JVM, `clojure -M:test -n yin.vm-test`:
```
Testing yin.vm-test

Ran 23 tests containing 170 assertions.
0 failures, 0 errors.
```

Node, `clj -M:cljs -m shadow.cljs.devtools.cli compile test` (log
`target/v2-cljs-test-r4.log`):
```
Ran 1361 tests containing 35506 assertions.
2 failures, 0 errors.
```
The count is 3 tests more than r3. The real-reader test is JVM-only and
does not run on Node. `yin.vm-test` reports no failures. The 2 failures
are the same ones seen since r1, in
`yin.repl.core-test/a-failed-input-is-consumed-exactly-once`
(`test/yin/repl_core_test.cljc:121`): the test expects `(/ 1 0)` to
throw, but JavaScript returns `##Inf`. They are unrelated to this unit.

ClojureDart: I checked the lane first. `pgrep -fl "cljd|dart|flutter|shadow.cljs|bb test"`
matched only the long-running `world.server` JVM, which matched only
because its classpath contains `src/cljd`. No cljd, dart, or test process
was running, so I ran `bb test:cljd` (log `target/v2-cljd-test-r4.log`).
**It failed at compile time again, before any test ran, on the same
unrelated file with a different error:**
```
:cause Unknown symbol: measure-ms at line: 113, column: 34, file: bench/yin_vm_bench.cljc
:message Error while compiling bench/yin_vm_bench.cljc measure-ms
Error while executing task: test:cljd
```
`test/bench/yin_vm_bench.cljc` is still **untracked** (`git status`:
`??`, no commits). The concurrent fix cleared r3's empty-`:require` NPE,
but the file now references an undefined `measure-ms` at line 113 on the
cljd host. It is outside my boundary, so I left it alone. **cljd
verification of this unit is therefore still outstanding**:
`yin.vm-test` has never executed on Dart. The file's owner needs to
define or guard `measure-ms` for cljd, or exclude the bench namespace
from the cljd test build.

## Boundaries

- Edited only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`,
  plus this file and test logs under `target/`.
- I did not touch the bench file, `dao.jing`, or any doc.
- Nothing staged or committed.
