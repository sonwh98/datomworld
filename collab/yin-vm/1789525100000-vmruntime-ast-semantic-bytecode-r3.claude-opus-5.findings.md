Created-GMT: 2026-09-15 21:58:20 GMT
Created-Local: 2026-09-16 04:58:20 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Findings: ast->semantic-bytecode r3 (2 fixes, 2 disclosed scope boundaries)
Role: VM Runtime
Implementer: claude-opus-5 | Status: complete (uncommitted working-tree diff)

## Method

I added the regression tests first and ran them against the r2 code with
`clojure -M:test -n yin.vm-test`:
```
Ran 19 tests containing 157 assertions.
6 failures, 0 errors.
```
All 6 failures are the new Finding 2 and Finding 5 assertions. After the
fix, the same command gives 0 failures (see Results).

## Finding 2: `strip-reader-positions` corrupted MapEntry payloads

**Reproduction (r2 code):** the reviewer's `{:type :literal :value (first {:a 1})}`.
```
FAIL ... a map entry payload stays the vector [k v]
expected: (vector? v)                 actual: (not (vector? (1 :a)))
expected: (= [:a 1] v)                actual: (not (= [:a 1] (1 :a)))
expected: (= (:root (vm/ast->semantic-bytecode (lit [:a 1]))) (:root bc))
  actual: (not (= :segment/sha256-87322fc4... :segment/sha256-1fe7833b...))
expected: (= [:a 1] (:value (vm/semantic-bytecode->ast bc)))
                                      actual: (not (= [:a 1] (1 :a)))
FAIL in (semantic-bytecode-round-trip-law) map -> rows -> map
  actual: (not (= {:type :literal, :value [:a 1]} {:type :literal, :value (1 :a)}))
```
The value changed to a reversed list and the address changed with it.

**Diff (`src/cljc/yin/vm.cljc:622-625`, in `strip-reader-positions`):**
```diff
-                 (or (set? x) (vector? x))
-                 (into (empty x) (map strip-reader-positions) x)
+                 (set? x) (into (empty x) (map strip-reader-positions) x)
+                 ;; not (into (empty x) ...): a map entry is vector? but its
+                 ;; empty is nil, which would rebuild it as a reversed list
+                 (vector? x) (mapv strip-reader-positions x)
```
`mapv` always builds a plain vector. The original vector's metadata, minus
reader positions, is still reattached by the existing `with-meta` at the
end of the function. Maps and sets keep `(into (empty x) ...)`, where
`empty` is reliable.

**Tests (`test/yin/vm_test.cljc`):**
- `:206`: `(lit (first {:a 1}))` added to `semantic-bytecode-corpus`, so
  both round-trip identities cover it. The corpus now has 29 entries.
- `:410-416`: in `semantic-bytecode-strips-reader-positions-inside-payloads`,
  new assertions that the projected slot is a vector equal to `[:a 1]`, that
  its root equals the root of `(lit [:a 1])`, and that reconstruction
  returns `[:a 1]`.

## Finding 5: params vector metadata broke the round trip

**Reproduction (r2 code):** a correctly hashed row
`[:lambda (with-meta '[x] {:purpose 1}) body-id]` (hashed with
`dao.jing/segment-key`, which includes vector metadata).
```
FAIL in (semantic-bytecode-lambda-params-are-syms) (v2_test.cljc:338)
a hashed params vector with metadata is refused, since projection
              would mint it without that metadata at another address
expected: (= :slot-kind (error-rule ...))
  actual: (not (= :slot-kind nil))
```
The row reconstructed without error. Re-projection would have produced a
metadata-free vector at a different address.

**Diff (`src/cljc/yin/vm.cljc:737-744`, `child` in `semantic-bytecode->ast`):**
```diff
-           :syms (if (and (vector? v) (every? symbol? v))
+           ;; projection always mints a metadata-free vector, so a vector
+           ;; carrying metadata (which dao.jing hashes) could never re-project
+           ;; to its own address
+           :syms (if (and (vector? v) (nil? (meta v)) (every? symbol? v))
                    v
                    (defect :slot-kind
-                           "Semantic bytecode syms slot is not a vector of symbols"
+                           "Semantic bytecode syms slot is not a metadata-free vector of symbols"
```
Metadata on the symbols *inside* params is still accepted. `dao.jing` does
not hash symbol metadata, and projection strips it. So such a row
re-projects to an `=` row at the same address, with no address change.

**Test:** `v2_test.cljc:336-340`, added to
`semantic-bytecode-lambda-params-are-syms`, asserts that the row is
refused with `:slot-kind`.

## Findings 1 and 4: disclosed, not fixed

As instructed there is no code change and no test. The exact wording added
to the `same-meta?` docstring (`src/cljc/yin/vm.cljc:634-645`):

```
   Scope boundary: metadata-on-metadata is not compared recursively. A value
   whose metadata map itself holds values carrying semantically distinct
   nested metadata is out of scope for the collision guard. The set branch
   assumes ordinary set semantics, at most one element per `=`-equivalence
   class; a set built with a comparator that admits several `=`-equal,
   metadata-distinct elements is out of scope. Both are the same class of
   pathological-input residual `dao.jing.md` documents for its own encoder
   (pathological symbols, scalar metadata).
```

The reviewer's Finding 3 (reader positions nested inside metadata maps
survive stripping) was not in this prompt's fix or scope-out lists. It has
the same metadata-on-metadata shape as Finding 1 and is also left
unhandled: `strip-reader-positions` does not recurse into metadata maps.
The disclosure above covers only the collision guard, not stripping. If
the Architect wants it disclosed explicitly for stripping too, it is a
one-sentence docstring addition to `strip-reader-positions`.

## Results after the fixes

JVM, `clojure -M:test -n yin.vm-test`:
```
Testing yin.vm-test

Ran 19 tests containing 157 assertions.
0 failures, 0 errors.
```

Node, `clj -M:cljs -m shadow.cljs.devtools.cli compile test` (log
`target/v2-cljs-test-r3.log`):
```
Ran 1358 tests containing 35497 assertions.
2 failures, 0 errors.
```
`yin.vm-test` reports no failures. The 2 failures are the same ones
seen in r1 and r2, in `yin.repl.core-test/a-failed-input-is-consumed-exactly-once`
(`test/yin/repl_core_test.cljc:121`): the test expects `(/ 1 0)` to
throw, but JavaScript returns `##Inf`. They are unrelated to this unit.

ClojureDart: I checked the lane first. `ps aux` showed no cljd, dart,
flutter, or shadow processes, only desktop apps and an unrelated
`world.server` JVM. So I ran `bb test:cljd` (log
`target/v2-cljd-test-r3.log`). **It failed at compile time, before any
test ran, for a reason unrelated to this unit:**
```
#error {
 :cause Cannot invoke "clojure.lang.Named.getName()" because "x" is null
 :via [{:message compiling namespace bench.yin-vm-bench ...}
       {:message Error while host-compiling bench/yin_vm_bench.cljc (ns bench.yin-vm-bench ... (:require [] [yin.vm :as vm] ...) ...}
       {:type java.lang.NullPointerException ... clojure.core$name}]
 :trace [[clojure.core$name ...] [cljd.compiler$ns_to_paths ...] [cljd.compiler$compile_namespace ...] ...]}
Error while executing task: test:cljd
```
The file is the **untracked** `test/bench/yin_vm_bench.cljc`, which is
not mine and is outside this unit's boundary. Its ns form has an empty `[]`
entry in `:require`. The cljd compiler calls `name` on that missing
namespace symbol and throws an NPE. `yin.vm` does not appear in the
failure; it merely sits in the same `:require` vector. So **cljd
verification of this unit is still outstanding**: `yin.vm-test` never
executed on Dart.

To unblock cljd, whoever owns that bench file can remove the empty `[]`
from its `:require`, or move the file out of the cljd test source path.
Cross-host points worth watching when it does run:
- `(first {:a 1})` must be `vector?` on Dart, or the MapEntry corpus entry
  is not exercised the same way;
- `empty` on sorted and hash sets/maps;
- `with-meta`/`meta` on symbols and vectors.

## Boundaries

- Edited only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`,
  plus this file and test logs under `target/`.
- I did not touch `dao.jing.cljc`, `dao.jing.md`, the design doc, or the
  bench file.
- No tests were added for Findings 1 and 4.
- Nothing staged or committed.
