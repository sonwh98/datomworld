# DHT head test findings

The prompt harness did not serve shell-owned link, service, and publication requests after evaluation. This left evaluations parked and prevented subsequent publication and head movement. The harness now drives the session as the DHT test harness does. A generic engine recheck also discarded previously reported pending links when its local round had no new report; it now retains them while the VM remains link-waiting. The harness rechecks after the shell supplies those links to render the pending notice. No assertions were removed.

Lint identified a nested FIFO deftest; its closing delimiter now makes it top level. The test is tagged slow and is excluded by the default test runner selection.

## Diff

```diff
diff --git a/src/cljc/yin/repl.cljc b/src/cljc/yin/repl.cljc
index 27dc68b9..2e82c09e 100644
--- a/src/cljc/yin/repl.cljc
+++ b/src/cljc/yin/repl.cljc
@@ -2669,7 +2669,10 @@ Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
   (let [parked (:pending-run state)]
     (try
       (let [[state' vm' pending progress?] (drive-links state (:vm parked)
-                                                        initial-progress)]
+                                                        initial-progress)
+            pending (if (and (empty? pending) (link-waiting? vm'))
+                      (:links parked)
+                      pending)]
         (if (vm/halted? vm')
           (let [vm'' (engine/restore-initial-env (:env (:base parked)) vm')
                 [st text] (finalize-eval state
diff --git a/test/yin/repl/dht_head_test.cljc b/test/yin/repl/dht_head_test.cljc
index 6ec5c190..a174adbe 100644
--- a/test/yin/repl/dht_head_test.cljc
+++ b/test/yin/repl/dht_head_test.cljc
@@ -162,8 +162,23 @@
   "Evaluate `line` at shell `k`: the world, its text appended to `k`'s
    lines."
   [w k line]
-  (let [[repl text] (repl/eval-input (get-in w [k :repl]) line)]
-    (-> w (assoc-in [k :repl] repl) (update-in [:lines k] conj text))))
+  (let [[engine text] (repl/eval-input (get-in w [k :repl]) line)
+        state (assoc (get w k) :repl engine)
+        [state text] (loop [state state text text budget 32]
+                       (let [{session :session progressed? :progress?}
+                             (shell.session/serve (main/session-of state))
+                             state (main/with-session state session)]
+                         (if (and progressed? (pos? budget))
+                           (let [[session resumed] (shell.session/recheck-pending session true)]
+                             (recur (main/with-session state session)
+                                    (or resumed text) (dec budget)))
+                           (if (and (seq (get-in state [:repl :pending-run :links]))
+                                    (str/includes? (str text) ";; require still pending"))
+                             (let [[session pending-text]
+                                   (shell.session/recheck-pending (main/session-of state))]
+                               [(main/with-session state session) pending-text])
+                             [state text]))))]
+    (-> w (assoc k state) (update-in [:lines k] conj text))))
 
 
 (defn- value
@@ -1230,10 +1245,10 @@
             (write-heads! dir (repl.dht/render-heads {:version 1 :heads {}}))
             (let [handle (open! dir (atom {}) false false)]
               (is (some? (:node handle)))
-              (durable/close! (:index-store handle))))))))
+              (durable/close! (:index-store handle)))))))))
 
 
-  #?(:cljd nil
+#?(:cljd nil
      :clj
      (deftest ^:slow a-fifo-at-heads-edn-is-refused-not-waited-on
        ;; In a child JVM, bounded: a regression blocks the child, not this
@@ -1257,4 +1272,4 @@
                    (do (.destroyForcibly child) "the child blocked"))]
          (is (zero? made) "mkfifo made the FIFO")
          (is done? "the read did not block on the FIFO")
-         (is (str/includes? out "it is not a regular file") out)))))
+         (is (str/includes? out "it is not a regular file") out))))
```

## Verification

`timeout 300 clj -M:test -n yin.repl.dht-head-test`:

```
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.repl.dht-head-test

Ran 23 tests containing 223 assertions.
0 failures, 0 errors.
```

`timeout 300 env DATOM_SLOW_TESTS=1 clj -M:test -n yin.repl.dht-head-test`:

```
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.repl.dht-head-test

Ran 23 tests containing 223 assertions.
0 failures, 0 errors.
```

`timeout 300 clj -M:test -n yin.repl.dht-test`:

```
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.repl.dht-test

Ran 46 tests containing 473 assertions.
0 failures, 0 errors.
```

`timeout 180 clj -M:test -n dao.host.gate-test`:

```
Loading initial Timbre config from: :default

Running tests in #{"test"}

Testing dao.host.gate-test

Ran 14 tests containing 178 assertions.
0 failures, 0 errors.
```

`timeout 180 clj -M:kondo --lint src/cljc/yin/repl.cljc test/yin/repl/dht_head_test.cljc`:

```
Loading initial Timbre config from: :default
linting took 1209ms, errors: 0, warnings: 0
```

Gate A: `rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` produced no output and exited 1 (no matches).

`git diff --check` produced no output and exited 0.
