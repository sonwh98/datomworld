# Dyn continuation producer r1

Base `4e2067b26bad086fe4b61e529afad99ca464bc44`, branch `fix/dyn-continuation-producer`; no commit. The requested ruling file is absent from the locally available `origin/master` (`26c87534fc8c333a91de0c1903705ed875d31cc5`); the dispatch's quoted ruling and constraints were applied.

## Loci and evaluation order

- `php/make-function`: bind the guest call's value before invoking `cell/set!`. The original resolves the `cell/set!` operator and result-cell operand before evaluating the guest computation; both are stable bindings, and reading them has no guest-visible effect. Callee, positional/named lookups, named placement, code lookup, arguments and guest invocation keep their original order. If the guest call escapes, the write remains unexecuted as before. The new order avoids holding the raw host setter across guest execution.
- `php/call-terminal`, `php/try`, `php/call-ec`: no change. Their capture applications implement continuation dispatch; no evidence identifies them as the pending host setter and reordering their capture/application would need a separate evaluation-order proof.
- `lower.cljc` function lowering and `prelude_module.cljc` include bodies: no change; no captured chain identified a distinct pending application at these loci. Initializers and include execution have their own sequencing and are not rewritten speculatively.
- Dyn fixture setup: each dyn row parses its own units and gets a fresh published runtime and link composition. `run-fixture` publishes the non-entry units, lowers each unit (which constructs its scope analysis), and runs all three backends. Expected values are unchanged.

## Verification

- Parser prerequisite: `bb gen:antlr -- --grammar php --target java`, exit 0.
- Adjacent `yang.php.antlr.scope-test`: 12 tests, 29 assertions, 0 failures, 0 errors.
- `yin.vm.debruijn-register-effects-test`: 12 tests, 148 assertions, 0 failures, 0 errors; receiver enforcement unchanged. A separate valid-payload probe replacing a saved register value with raw `yin.vm.module/set-cell!` returns `{:rule :continuation-registers}`.
- `bb test:sub:clj dao.stream`: exit 1, 41 namespaces, 589 tests, 5624 assertions, 14 failures, 13 errors; socket tests report `java.net.SocketException: Operation not permitted` in this restricted environment. Copied log: `target/dyn-r1/dao-stream-sub-clj.log`.
- Initial `bb test:sub:clj yang.php` attempt: exit 1 before tests, missing `yang.typescript.antlr.gen.TypeScriptLexer`; log copied to `target/dyn-r1/yang-php-sub-clj.log`. Generating all Java parsers hit the sandbox socket restriction. The TypeScript and Python parser build artifacts were copied from `/home/sto/ci/datomworld-phpgate` after verifying both grammar manifests identical; the gate was restarted.

## Exact diff

```diff
diff --git a/src/cljc/yang/php/antlr/prelude.cljc b/src/cljc/yang/php/antlr/prelude.cljc
index dbe4a374..b238b587 100644
--- a/src/cljc/yang/php/antlr/prelude.cljc
+++ b/src/cljc/yang/php/antlr/prelude.cljc
@@ -2734,17 +2734,17 @@
                                      (let [result (cell/new :php/undef)]
                                        (php/try
                                          (fn []
-                                           (cell/set! result
-                                                      (let [callee (get invocation :yang.call/callee)
-                                                            positional (get invocation :yang.call/positional)
-                                                            named (get invocation :yang.call/named)
-                                                            args (if (> (data/count named) 0)
-                                                                   (php/place-named callee (get (cell/get callee) :spec)
-                                                                                    positional named 0 (data/count named)
-                                                                                    (php/empty-array))
-                                                                   positional)]
-                                                        ((get (cell/get callee) :code)
-                                                         callee args binding-profile))))
+                                           (let [callee (get invocation :yang.call/callee)
+                                                 positional (get invocation :yang.call/positional)
+                                                 named (get invocation :yang.call/named)
+                                                 args (if (> (data/count named) 0)
+                                                        (php/place-named callee (get (cell/get callee) :spec)
+                                                                         positional named 0 (data/count named)
+                                                                         (php/empty-array))
+                                                        positional)
+                                                 value ((get (cell/get callee) :code)
+                                                        callee args binding-profile)]
+                                             (cell/set! result value)))
                                          (fn [e]
                                            (let [retained (php.rt/retained-condition e)]
                                              (if (nil? retained)
diff --git a/test/yang/php/antlr/include_test.clj b/test/yang/php/antlr/include_test.clj
index 2b7ae936..92c858e6 100644
--- a/test/yang/php/antlr/include_test.clj
+++ b/test/yang/php/antlr/include_test.clj
@@ -38,8 +38,14 @@
 
 (defn- run
   [fixture]
-  (lh/run-fixture (parse-units fixture) "main.php"
-                  {:computed? (:computed fixture), :class-map (:class-map fixture)}))
+  (let [units (parse-units fixture)
+        opts {:computed? (:computed fixture), :class-map (:class-map fixture)}]
+    (if (= :dynscope-v1 (:group fixture))
+      ;; Publish this row's units into its own runtime and compose its link
+      ;; source there. Lowering each packet constructs its own scope analysis.
+      (with-redefs [lh/published (delay (lh/make-published))]
+        (lh/run-fixture units "main.php" opts))
+      (lh/run-fixture units "main.php" opts))))
 
 
 (deftest ^:slow p6-fixtures-linked-on-every-vm-test

```

## Incomplete gates

- The two fresh JVM `clojure -M:test -n yang.php.antlr.include-test` runs (`include-cold-2.log` and `include-cold-3.log`) were interrupted after several minutes, exit 130, without a test summary. Both printed cyclic initialization on semantic, stack and register; this is not proof that the dyn rows or the namespace pass. An earlier `include-cold-1.log` was interrupted after fixture edits and is not a post-change gate.
- The restarted `bb test:sub:clj yang.php` was interrupted, exit 130, while in `yang.php.antlr.builtin-surfaces-test`, with no test summary. Partial log copied immediately to `target/dyn-r1/yang-php-retry-sub-clj.log`. The preceding closure gate recorded in `1794813199000-engineer-regset-fix-r3.gpt-6.1-terra.findings.md` took 7,587 seconds. The requested cold-start proof and complete PHP gate remain outstanding; do not treat this as a verified fix.
- A focused dyn probe was also interrupted without a result (`target/dyn-r1/dyn-probe.log`).
