# DHT process and live CLI fixes

The publication parser accepts acknowledged published and republished manifests; the existing HEAD comparison remains in place. The live driver invokes a generic post-input composition hook for a newly parked run; the shell composition serves its media and rechecks, exposing the pending link name. A loopback test exercises the real step owner. The socketless process regression passed: no shutdown fix was needed. Socketed process vars were not run in this sandbox.

## Socketless process test
Command: clj -M:test -v yin.repl.dht-process-test/prompt-returns-after-filtered-comprehension-assignment-test
```text
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.repl.dht-process-test

Ran 1 tests containing 4 assertions.
0 failures, 0 errors.
```

## Focused live-loop regression
```text
Testing yin.repl.dht-head-test

Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
```

## Node build
Command: bb build:yin-repl-node (mise Babashka and Node on PATH)
```text
npm-ci cache hit (inputs and outputs verified)
antlr cache hit (inputs and outputs verified)
Loading initial Timbre config from: :default
[:yin-repl] Compiling ...
[:yin-repl] Build completed. (258 files, 19 compiled, 0 warnings, 7.39s)
```

## Lint
Command: clj -M:kondo --lint on the five modified source and test files
```text
[0.002s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default
linting took 1394ms, errors: 0, warnings: 0
```

## Gate A
The requested rg returned exit status 1 and no matches.

## Diff
```diff
diff --git a/src/cljc/yin/repl/main.cljc b/src/cljc/yin/repl/main.cljc
index dae873a0..846ea80c 100644
--- a/src/cljc/yin/repl/main.cljc
+++ b/src/cljc/yin/repl/main.cljc
@@ -859,6 +859,14 @@
             comp-lines (cond-> comp-lines
                          (seq rechecked) (conj rechecked))
             stepped (driver/repl-step state now)
+            [stepped after-input] (if (and (nil? (get-in state [:repl :pending-run]))
+                                           (get-in stepped [:repl :pending-run])
+                                           (:recheck-after-input (:composition stepped))
+                                           (session-of stepped))
+                                    (let [[session text] ((:recheck-after-input (:composition stepped))
+                                                          (session-of stepped))]
+                                      [(with-session stepped session) text])
+                                    [stepped nil])
             stepped (if (:running? stepped)
                       (custody-program-step stepped)
                       (custody-stop stepped))
@@ -870,9 +878,10 @@
                                         (serve/take-outbox server')
                                         [[] nil])
             state'' (if server'' (assoc state' :repl (:repl server'')) state')]
-        [state'' server'' (into comp-lines
-                                (map entry-text)
-                                (into (vec entries) server-entries))]))))
+        [state'' server'' (cond-> (into comp-lines
+                                        (map entry-text)
+                                        (into (vec entries) server-entries))
+                            (seq after-input) (conj after-input))]))))
 
 
 (defn moved?
@@ -1080,7 +1089,8 @@
       (flush)
       (if (:running? state')
         (do (when (and (not headless?) (prompt-owed? state state' lines))
-              (do (print prompt) (flush)))
+              (print prompt)
+              (flush))
             (let [{:keys [cadence-state sleep-ms]}
                   (cadence/cadence-step cadence
                                         (moved? state' server' lines))]
diff --git a/src/cljc/yin/shell/main.cljc b/src/cljc/yin/shell/main.cljc
index fbceab3c..2b256322 100644
--- a/src/cljc/yin/shell/main.cljc
+++ b/src/cljc/yin/shell/main.cljc
@@ -56,6 +56,7 @@
           :close-store! shell.store/close!
           :step shell.dht/step
           :recheck-on-tick shell.session/recheck-on-tick
+          :recheck-after-input shell.session/recheck-after-input
           :stop! shell.dht/stop!
           :stopped? shell.dht/stopped?
           :close! shell.dht/close!
diff --git a/src/cljc/yin/shell/session.cljc b/src/cljc/yin/shell/session.cljc
index 56d47269..3ef6bc1d 100644
--- a/src/cljc/yin/shell/session.cljc
+++ b/src/cljc/yin/shell/session.cljc
@@ -152,6 +152,13 @@
      [(assoc session :engine engine) text])))
 
 
+(defn recheck-after-input
+  "Serve requests made by newly evaluated input and report its parked run."
+  [session]
+  (let [{session :session progress? :progress?} (serve session)]
+    (recheck-pending session progress?)))
+
+
 (defn settle-publication
   "`session` after the composition's local publication service has been
   stepped and every result it delivered consumed.  A composition that
diff --git a/test/yin/repl/dht_head_test.cljc b/test/yin/repl/dht_head_test.cljc
index a174adbe..d9c4036a 100644
--- a/test/yin/repl/dht_head_test.cljc
+++ b/test/yin/repl/dht_head_test.cljc
@@ -22,6 +22,7 @@
             [dao.stream :as stream]
             [dao.stream.loopback-net :as net]
             [yin.repl :as repl]
+            [yin.repl.driver :as driver]
             [yin.shell.dht :as repl.dht]
             [yin.repl.main :as main]
             [yin.shell.cli :as cli]
@@ -291,6 +292,17 @@
 ;; Following: first contact, the moved line, (reset), and a bare restart
 ;; =============================================================================
 
+(deftest live-loop-reports-the-name-of-a-new-pending-require
+  (let [key (sign/generate)
+        w (world)
+        w (assoc w :b (reader w key (temp-dir)))
+        state (:b w)]
+    (driver/submit-line! (:input state) "(require (quote my.lib))")
+    (let [[state _ lines] (main/step-all state nil 0)]
+      (is (some #(str/includes? % ";; require pending: my.lib") lines)
+          (pr-str lines))
+      (close! (assoc w :b state)))))
+
 (deftest ^:slow a-reader-follows-requires-moves-and-restarts-from-heads-edn
   (slow/guard
     "a-reader-follows-requires-moves-and-restarts-from-heads-edn"
diff --git a/test/yin/repl/dht_process_test.clj b/test/yin/repl/dht_process_test.clj
index fa2238b0..3703b43b 100644
--- a/test/yin/repl/dht_process_test.clj
+++ b/test/yin/repl/dht_process_test.clj
@@ -574,7 +574,7 @@
 
 
 (def ^:private published-line
-  #"dht: published :(segment/\S+) .*, acknowledged")
+  #"dht: (?:re)?published :(segment/\S+) .*, acknowledged")
 
 
 (defn- head-of-dir
```

## Combined test attempt
The main-test namespace installs a real ServerSocket once-fixture, which the sandbox rejects. This attempt aborted before the remaining namespaces; socketed process-test vars were not run.
```text
[0.002s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing dao.host.gate-test

Testing yin.repl.dht-head-test

Testing yin.repl.dht-test

Testing yin.repl.main-test
Execution error (SocketException) at sun.nio.ch.Net/socket0 (Net.java:-2).
Operation not permitted

Full report at:
/tmp/clojure-804632206870626935.edn
```

## Isolated main-test vars
The namespace once-fixture and its socket-dependent vars (lines 1060–1210) were omitted. The test runner printed its summary; the JVM remained alive afterward and was interrupted.
```text
Loading initial Timbre config from: :default
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load
Bye

Bye


Ran 44 tests containing 373 assertions.
0 failures, 0 errors.
```

## Gate, DHT and DHT-head suites
Command: clj -M:test -n yin.repl.dht-test -n yin.repl.dht-head-test -n dao.host.gate-test
```text
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing dao.host.gate-test

Testing yin.repl.dht-head-test

Testing yin.repl.dht-test

Ran 84 tests containing 875 assertions.
0 failures, 0 errors.
```

## Shell-session suite
Command: clj -M:test -n yin.repl.shell-session-test
```text
[0.002s][warning][perf,memops] Cannot use file /tmp/hsperfdata_sto/3 because it is locked by another process (errno = 11)
Loading initial Timbre config from: :default

Running tests in #{"test"}

Testing yin.repl.shell-session-test

Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
```
