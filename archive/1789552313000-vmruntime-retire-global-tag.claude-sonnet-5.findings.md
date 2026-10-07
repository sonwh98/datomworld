Created-GMT: 2026-09-16 03:05:13 GMT
Created-Local: 2026-09-16 10:05:13 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 08f4ae37-6125-471f-af73-91e66c549bd7
# Findings: Retire :global from ast->semantic-bytecode

## Summary

Removed the `:global` tag from `semantic-bytecode-grammar` in
`src/cljc/yin/vm.cljc` (18 -> 17 entries) and removed all `:global`
usage from `test/yin/vm_test.cljc`. Only the two files named in the
prompt's Boundaries section were touched. Nothing staged or committed.

## Changes

`src/cljc/yin/vm.cljc`:
- Removed the `:global [[:name :sym]]` entry from `semantic-bytecode-grammar`.
  Grammar now has 17 tags (verified by counting map entries).

`test/yin/vm_test.cljc`:
- Removed the `global` test-helper function (`{:type :global, :name n}`).
- Replaced every `(global 'x)` call in `semantic-bytecode-corpus` and
  elsewhere with the existing `(local 'x)` helper (which already
  constructs plain `:variable` nodes) — no new helper needed.
- Updated the row-shape assertion that checked a projected row equalled
  `[:global +]` to `[:variable +]`.
- Updated the two explanatory comments/strings that named `:global`
  (`":if, :application, :global, one :literal"` ->
  `":if, :application, :variable, one :literal"`).
- `semantic-bytecode-corpus-covers-every-tag` needed no code change: it
  already asserts `(set (keys grammar)) = (set of tags produced by the
  corpus)` generically, so once the corpus stopped emitting `:global`
  rows and the grammar stopped listing `:global`, the check passes
  against the reduced 17-tag grammar automatically.

Full diff attached below for reference (also visible via `git diff`).

## Test results

```
clojure -M:test -n yin.vm-test
Ran 23 tests containing 172 assertions.
0 failures, 0 errors.
```

Before/after: 23 tests / 172 assertions in both states. No test or
assertion was deleted — every site that referenced `:global` was a
tag/name substitution (`global` -> `local`, `[:global +]` -> `[:variable
+]`, comment text), not a removed assertion, so the count did not
shrink. Coverage of `:variable` increased (more corpus entries now
produce `:variable` rows) while `:global` coverage disappeared along
with the tag itself.

## Diff

```diff
diff --git a/src/cljc/yin/vm.cljc b/src/cljc/yin/vm.cljc
index 3527d87..ae16f92 100644
--- a/src/cljc/yin/vm.cljc
+++ b/src/cljc/yin/vm.cljc
@@ -572,7 +572,6 @@
    table drives both projection and reconstruction."
   {:literal [[:value :data]],
    :variable [[:name :sym]],
-   :global [[:name :sym]],
    :lambda [[:params :syms] [:body :node]],
    :application [[:operator :node] [:operands :nodes] [:tail? :bool]],
    :if [[:test :node] [:consequent :node] [:alternate :node]],
diff --git a/test/yin/vm_test.cljc b/test/yin/vm_test.cljc
index 1a8d3c1..b69934d 100644
--- a/test/yin/vm_test.cljc
+++ b/test/yin/vm_test.cljc
@@ -168,11 +168,6 @@
   {:type :variable, :name n})
 
 
-(defn- global
-  [n]
-  {:type :global, :name n})
-
-
 (defn- app
   [operator operands tail?]
   {:type :application, :operator operator, :operands operands, :tail? tail?})
@@ -182,18 +177,18 @@
   "Canonical map ASTs (every saturated field stated) covering every §2.3 tag."
   [(lit 1) (lit "s") (lit nil) (lit :k) (lit '[1 (2 3) #{4}]) (lit {:a 1})
    (local 'x)
-   (global '+)
+   (local '+)
    {:type :lambda, :params [], :body (lit 0)}
    {:type :lambda, :params '[x], :body (local 'x)}
    {:type :lambda,
     :params '[x y z],
-    :body (app (global '+) [(local 'x) (local 'y) (local 'z)] true)}
-   (app (global 'f) [] false)
-   (app (global 'f) [(lit 1)] true)
+    :body (app (local '+) [(local 'x) (local 'y) (local 'z)] true)}
+   (app (local 'f) [] false)
+   (app (local 'f) [(lit 1)] true)
    (app {:type :lambda, :params '[x], :body (local 'x)} [(lit 1) (lit 2)] false)
    {:type :if,
-    :test (app (global '<) [(lit 1) (lit 2)] false),
-    :consequent (app (global 'f) [(lit 41)] true),
+    :test (app (local '<) [(lit 1) (lit 2)] false),
+    :consequent (app (local 'f) [(lit 41)] true),
     :alternate (lit 0)}
    {:type :dao.stream.apply/call, :op :op/none, :operands []}
    {:type :dao.stream.apply/call, :op :op/add, :operands [(lit 1) (lit 2)]}
@@ -236,14 +231,14 @@
   (testing "the §2.1 example projects to [id tag & slots] with child ids"
     (let [ast {:type :lambda,
                :params '[x],
-               :body (app (global '+) [(local 'x) (lit 1)] true)}
+               :body (app (local '+) [(local 'x) (lit 1)] true)}
           {:keys [root rows]} (vm/ast->semantic-bytecode ast)
           [a tag params b] (get rows root)
           [_ _ c [d e] tail?] (get rows b)]
       (is (= [root :lambda '[x]] [a tag params]))
       (is (= :application (second (get rows b))))
       (is (true? tail?))
-      (is (= '[:global +] (subvec (get rows c) 1)))
+      (is (= '[:variable +] (subvec (get rows c) 1)))
       (is (= '[:variable x] (subvec (get rows d) 1)))
       (is (= [:literal 1] (subvec (get rows e) 1)))
       (is (= 5 (count rows)))
@@ -255,7 +250,7 @@
 (deftest semantic-bytecode-structural-sharing
   (let [ast {:type :if,
              :test (lit 1),
-             :consequent (app (global 'f) [(lit 1)] true),
+             :consequent (app (local 'f) [(lit 1)] true),
              :alternate (lit 1)}
         {:keys [root rows], :as bc} (vm/ast->semantic-bytecode ast)
         [_ _ test-id cons-id alt-id] (get rows root)
@@ -263,7 +258,7 @@
     (is (= test-id alt-id operand-id)
         "independently built identical subtrees mint one id")
     (is (= 1 (count (filter #(= :literal (second %)) (vals rows)))))
-    (is (= 4 (count rows)) ":if, :application, :global, one :literal")
+    (is (= 4 (count rows)) ":if, :application, :variable, one :literal")
     (is (= ast (vm/semantic-bytecode->ast bc)))))
 
 
@@ -271,9 +266,9 @@
   (is (= (vm/ast->semantic-bytecode (lit 1))
          (vm/ast->semantic-bytecode
            {:type :literal, :value 1, :tail? true, :eid -5, :yang/pos [1 2]})))
-  (is (= (vm/ast->semantic-bytecode (app (global 'f) [] false))
+  (is (= (vm/ast->semantic-bytecode (app (local 'f) [] false))
          (vm/ast->semantic-bytecode {:type :application,
-                                     :operator (global 'f)})))
+                                     :operator (local 'f)})))
   (is (= (vm/ast->semantic-bytecode {:type :lambda,
                                      :params '[x],
                                      :body (lit 1)})
@@ -291,7 +286,7 @@
 
 (deftest semantic-bytecode-reconstruction-validates
   (let [{:keys [root rows], :as bc}
-        (vm/ast->semantic-bytecode (app (global 'f) [(lit 1)] true))
+        (vm/ast->semantic-bytecode (app (local 'f) [(lit 1)] true))
         [_ _ op-id _ _] (get rows root)
         reroot (fn [row]
                  (let [id (jing/segment-key (subvec row 1))]
@@ -361,7 +356,7 @@
 
 
 (deftest semantic-bytecode-bool-slot-is-validated
-  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (global 'f) [] false))
+  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (local 'f) [] false))
         [_ _ op-id operand-ids] (get rows root)]
     (is (= :slot-kind
            (error-rule #(vm/semantic-bytecode->ast
@@ -482,7 +477,7 @@
 
 
 (deftest semantic-bytecode-nodes-vector-metadata-is-refused
-  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (global 'f) [] false))
+  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (local 'f) [] false))
         [_ _ op-id] (get rows root)]
     (is (= :slot-kind
            (error-rule #(vm/semantic-bytecode->ast
```
