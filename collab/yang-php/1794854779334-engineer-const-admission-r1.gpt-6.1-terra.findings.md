# Constant admission r1

Base `f32a4392935f8ad455f8fd7d94e9679815619c3b`, branch `fix/const-admission`. No commits. Ruling read from `origin/master:collab/yang-php/1794850396000-architect-constant-admission-ruling.findings.md`.

Removed the bare global refusal in `constant-node`, retaining namespaced fallback, builtin folding and `php/const-get-fq` for the remaining names. No production prelude, admission, linker, continuation or class-fatal enforcement changed. Undefined names remain catchable `Error`; constant redeclaration retains `php/warn 2` and false. No warmup, runtime composition, preflight or rollback changes.

The stale e2e FOO refusal is replaced with an unhandled runtime Error guard. The P6 fixture comparison checks fatal/status and uncaught for constant rows; the dedicated slow linked test covers the four specified fixtures on all backends and checks catch/finally, unhandled Error, falsey constants, read-before-declaration, and fresh request isolation. An additional runtime control checks namespace and builtin resolution and a handler receiving redeclaration warning 2.

## Results

- Quick: selected e2e refusal/undefined-name, builtin lowering and duplicate-class tests: 4 tests, 36 assertions, 0 failures, 0 errors (`quick-selected.log`). Namespace/builtin and warning-handler control: 1 test, 5 assertions, 0 failures, 0 errors (`namespace-handler.log`). Site-set and register-effects suites: 20 tests, 182 assertions, 0 failures, 0 errors (`admission-register.log`).
- Foundation `bb test:sub:clj dao.stream`: exit 1, 41 namespaces, 589 tests, 5622 assertions, 14 failures and 13 errors; datagram bind errors report `java.net.SocketException: Operation not permitted` under this sandbox. Log copied to `target/const-r1/dao-stream-sub-clj.log` before the PHP gate.
- An initial combined quick command failed before running tests because `-n` selection excluded namespaces referenced with `-v`; its log is `quick-regressions.log`. Corrected runs are listed above.
- Quick subsystem selection excludes `^:slow` by default, so its result cannot establish the four linked rows; their separate fresh-process `^:slow` test is required.
- Slow fresh JVM `clojure -M:test -v yang.php.antlr.include-test/constant-admission-linked-test`: exit 0, 1 test, 90 assertions, 0 failures, 0 errors (`linked-slow.log`). Each of the four specified rows printed the expected output, diagnostics and nil uncaught on semantic, stack and register; the additional exception, status, fatal, falsey, ordering and fresh-request guards passed in the same run.

## Patch

```diff
diff --git a/src/cljc/yang/php/antlr/lower.cljc b/src/cljc/yang/php/antlr/lower.cljc
index 2da68c6c..98856ff4 100644
--- a/src/cljc/yang/php/antlr/lower.cljc
+++ b/src/cljc/yang/php/antlr/lower.cljc
@@ -548,21 +548,11 @@
    folds in the global block and at fully qualified reads; an unqualified
    read inside a namespace is the two-key run-time lookup, builtins
    included (plan P6 R7)."
-  [ctx n q]
+  [ctx _n q]
   (let [{:keys [primary fallback]} (resolve-global-name ctx :const q)]
     (cond
       fallback (app* 'php/const-get (u/lit primary) (u/lit fallback))
       (contains? builtins/constants primary) (builtin-constant-node (get builtins/constants primary))
-      ;; Global bare names have no namespace fallback. Until ordinary global
-      ;; constant declarations are statically resolved, accepting one here
-      ;; defers a known unsupported construct to a runtime undefined-constant
-      ;; error and loses the lowering diagnostic contract.
-      (and (= 1 (count (:segs q)))
-           (not (:lead? q))
-           (not (:ns-kw? q))
-           (not (contains? (get-in ctx [:uses :const]) (first (:segs q))))
-           (= "" (:ns ctx "")))
-      (unsupported! n :constant)
       :else (app* 'php/const-get-fq (u/lit primary)))))
 
 
diff --git a/test/yang/php/antlr/e2e_test.clj b/test/yang/php/antlr/e2e_test.clj
index 5101563c..df7ee272 100644
--- a/test/yang/php/antlr/e2e_test.clj
+++ b/test/yang/php/antlr/e2e_test.clj
@@ -232,7 +232,6 @@
            ["<?php function f(&...$a) {}" :variadic-reference]
            ["<?php $a = &new B;" :reference]
            ["<?php static $a = 1;" :static]
-           ["<?php echo FOO;" :constant]
            ["<?php echo \"a$b[0]\";" :interpolation]
            ["<?php echo \"a$b->c\";" :interpolation]
            ["<?php echo (array)1;" :cast/array]
@@ -244,6 +243,28 @@
       (is (= construct (:construct d)) source))))
 
 
+(deftest undefined-bare-constant-is-a-runtime-error-test
+  (let [r (h/run-raw "<?php echo FOO; echo 'after';")]
+    (is (= "" (:php/out r)))
+    (is (= {:class "Error", :message "Undefined constant \"FOO\""}
+           (select-keys (:php/uncaught r) [:class :message])))
+    (is (= 255 (:php/status r)))
+    (is (nil? (:php/fatal r)))))
+
+
+(deftest constant-resolution-and-warning-handler-test
+  (let [r (h/run-raw (str "<?php namespace A; const V = 'local'; "
+                          "echo V, PHP_EOL === \\PHP_EOL ? 'builtin' : 'bad'; "
+                          "set_error_handler(function ($n, $s) { echo $n, ':', $s; return true; }); "
+                          "define('X', 1); var_dump(define('X', 2)); echo \\X;"))]
+    (is (= "localbuiltin2:Constant X already definedbool(false)\n1"
+           (h/latin1->text (:php/out r))))
+    (is (empty? (:php/diag r)))
+    (is (nil? (:php/uncaught r)))
+    (is (nil? (:php/fatal r)))
+    (is (= 0 (get r :php/status 0)))))
+
+
 (deftest variable-variable-offset-assign-test
   (testing "P9: a variable-variable base with an offset assigns through (${$a}[0])"
     (is (= "1,20"
diff --git a/test/yang/php/antlr/include_test.clj b/test/yang/php/antlr/include_test.clj
index 2b7ae936..4dc6bf02 100644
--- a/test/yang/php/antlr/include_test.clj
+++ b/test/yang/php/antlr/include_test.clj
@@ -53,11 +53,43 @@
       (is (not= :banner oracle))
       (is (= (set (keys lh/backends)) (set (keys (:values r)))) (str (name id) " backends"))
       (doseq [[k v] (:values r)]
-        (is (= [out (vec diag) uncaught] (observed v)) (str (name id) " on " k)))
+        (is (= [out (vec diag) uncaught] (observed v)) (str (name id) " on " k))
+        (when (= :constants-v1 (:group fixture))
+          (is (nil? (:php/uncaught v)) (str id " " k))
+          (is (nil? (:php/fatal v)) (str id " " k))
+          (is (= 0 (get v :php/status 0)) (str id " " k))))
       (when warnings
         (is (= warnings (mapv :yang.php/diagnostic (:warnings r))) (name id))))))
 
 
+(deftest ^:slow constant-admission-linked-test
+  (let [ids #{:const-declare-and-read :const-redeclare-warns
+              :const-undefined-error :const-define-redefinition}]
+    (doseq [{:keys [id out diag uncaught] :as fixture} (filter #(ids (:id %)) p6/enabled)]
+      (let [values (:values (run fixture))]
+        (is (= (set (keys lh/backends)) (set (keys values))) (str id))
+        (doseq [[backend value] values]
+          (println "constant-fixture" id backend (pr-str (observed value)))
+          (is (= [out (vec diag) uncaught] (observed value)) (str id " " backend))
+          (is (nil? (:php/fatal value)) (str id " " backend))
+          (is (= 0 (get value :php/status 0)) (str id " " backend))))))
+  (doseq [[label source out diag uncaught status]
+          [[:uncaught "<?php echo NOPE; echo 'after';" "" []
+            {:class "Error", :message "Undefined constant \"NOPE\""} 255]
+           [:caught-finally "<?php try { echo NOPE; } catch (Error $e) { echo 'caught'; } finally { echo 'finally'; }" "caughtfinally" [] nil 0]
+           [:values "<?php define('X', 7); echo X; define('F', false); define('Z', 0); define('N', null); var_dump(F, Z, N);" "7bool(false)\nint(0)\nNULL\n" [] nil 0]
+           [:read-before-declaration "<?php echo LATER; const LATER = 1;" "" []
+            {:class "Error", :message "Undefined constant \"LATER\""} 255]
+           [:fresh-request "<?php echo X;" "" []
+            {:class "Error", :message "Undefined constant \"X\""} 255]]]
+    (let [values (:values (run {:source source}))]
+      (is (= (set (keys lh/backends)) (set (keys values))) (name label))
+      (doseq [[backend value] values]
+        (is (= [out diag uncaught] (observed value)) (str label " " backend))
+        (is (= status (get value :php/status 0)) (str label " " backend))
+        (is (nil? (:php/fatal value)) (str label " " backend))))))
+
+
 (deftest ^:slow mutual-include-ticks-on-every-vm-test
   (let [base (some #(when (= :inc-mutual-once (:id %)) %) p6/enabled)]
     (doseq [[label fixture expected-sites]

```
