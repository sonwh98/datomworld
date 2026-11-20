# Read-only dw-dartleg evidence snapshot (feat/dart-leg-linked-fixtures@657f01ff)

## git status --short
```text
 M .flutter-plugins-dependencies
 M src/cljc/dao/stream/base64.cljc
 M test/dao/stream/base64_test.cljc
 M test/yang/php/antlr/linked_harness.cljc
?? collab/yang-php/1795099946000-engineer-dart-leg-r1.gpt-6.1-terra.findings.md
?? collab/yang-php/1795106342000-engineer-dart-leg-r2.gpt-6.1-terra.findings.md
?? collab/yang-php/1795199576371-architect-dart-publication-perf-consult.gpt-6.2-astra.stdout.log
?? collab/yang-php/1795199576371-architect-dart-publication-perf-consult.prompt.md
?? src/dev/yang/php/dart_linked_packets.clj
?? test/yang/php/antlr/dart_linked_packets.cljc
?? test/yang/php/antlr/dart_linked_test.cljc
```

## git diff (base64.cljc, base64_test.cljc, linked_harness.cljc)
```diff
diff --git a/src/cljc/dao/stream/base64.cljc b/src/cljc/dao/stream/base64.cljc
index b6154fc5..4cde4181 100644
--- a/src/cljc/dao/stream/base64.cljc
+++ b/src/cljc/dao/stream/base64.cljc
@@ -12,14 +12,6 @@
     [dao.host.bytes :as bytes]))
 
 
-(def ^:private text-pattern
-  "Padded standard-alphabet Base64, anchored at both ends:
-   no whitespace, no URL-safe alphabet. A length-4k tail is 4 alphabet characters,
-   a length-4k+2 tail is two characters and two pads, a length-4k+3 tail is three
-   characters and one pad, and no other shape is text."
-  #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
-
-
 (def ^:private ^String alphabet
   "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
 
@@ -33,7 +25,21 @@
   "True iff `s` is strict padded standard-alphabet Base64 text, the empty
    string included: exactly what a datagram event's :bytes field carries."
   [s]
-  (and (string? s) (boolean (re-matches text-pattern s))))
+  ;; A repeated four-character regex overflows Dart's regexp stack on large
+  ;; valid content-pair payloads. Scan the same alphabet and padding shape
+  ;; iteratively, in constant stack space on every host.
+  (and (string? s)
+       (let [n (count s)]
+         (and (zero? (mod n 4))
+              (let [padding (if (and (pos? n) (= \= (nth s (dec n))))
+                              (if (and (> n 1) (= \= (nth s (- n 2)))) 2 1)
+                              0)
+                    end (- n padding)]
+                (loop [i 0]
+                  (if (= i end)
+                    true
+                    (and (contains? decode-table (nth s i))
+                         (recur (inc i))))))))))
 
 
 (defn encode
diff --git a/test/dao/stream/base64_test.cljc b/test/dao/stream/base64_test.cljc
index f3ac18d1..d9f4ae66 100644
--- a/test/dao/stream/base64_test.cljc
+++ b/test/dao/stream/base64_test.cljc
@@ -98,3 +98,10 @@
             encoded (base64/encode (apply b octets))]
         (is (base64/text? encoded))
         (is (bytes= (apply b octets) (base64/decode encoded)))))))
+
+
+(deftest large-payload-validation-is-stack-safe
+  (let [chunk (apply str (repeat 1000 "A"))
+        payload (apply str (repeat 4000 chunk))]
+    (is (base64/text? payload))
+    (is (not (base64/text? (str (subs payload 0 (- (count payload) 4)) "A!AA"))))))
diff --git a/test/yang/php/antlr/linked_harness.cljc b/test/yang/php/antlr/linked_harness.cljc
index 1d42022b..fde0da6b 100644
--- a/test/yang/php/antlr/linked_harness.cljc
+++ b/test/yang/php/antlr/linked_harness.cljc
@@ -65,26 +65,28 @@
   "`units` (`[{:name path :packet p :strict-types b}]`) published into
    `php`'s own content store, where its pinned requirement already lives:
    `publication/publish-units!`'s answer."
-  [units]
-  (publication/publish-units! (:store @published) (registry) (:address @published) units))
+  ([units] (publish-units! @published units))
+  ([runtime units]
+   (publication/publish-units! (:store runtime) (registry) (:address runtime) units)))
 
 
 (defn source
   "The linked store and signed site publications for this fixture."
   ([name-env] (source name-env nil))
-  ([name-env result]
+  ([name-env result] (source @published name-env result))
+  ([runtime name-env result]
    (let [publisher (sign/generate)
          entries (siteset/signed-entries publisher (:sites result)
                                          (:addresses result) (:bundles result))
          datoms (siteset/entry-datoms entries)
          stage (siteset/stage (registry)
                               {:hooks true, :hooks-profile safepoint/ticks-profile,
-                               :php-sp (:hook-address @published)} {})]
+                               :php-sp (:hook-address runtime)} {})]
      (link/composition
-       {:content-store (:store @published),
-        :name-env (merge {'php (:address @published),
-                          'php.sp (:hook-address @published),
-                          'yang.call (:call-address @published)} name-env),
+       {:content-store (:store runtime),
+        :name-env (merge {'php (:address runtime),
+                          'php.sp (:hook-address runtime),
+                          'yang.call (:call-address runtime)} name-env),
         :derivation :trusted,
         :stage (fn [ctx name address]
                  (stage (assoc ctx :datoms datoms :principals [(:public publisher)])
@@ -155,11 +157,12 @@
    lowered `:linked` and run. `value` is `run-main`'s map."
   ([units] (run-fixture units lower/entry-path {}))
   ([units main opts]
-   (let [entry (get units main)
+   (let [runtime (or (:runtime opts) @published)
+         entry (get units main)
          others (cond-> (mapv (fn [[path packet]] {:name path, :packet packet})
                               (dissoc units main))
                   (:class-map opts) (conj (assoc (:class-map opts) :name lower/classmap-name)))
-         result (publish-units! others)]
+         result (publish-units! runtime others)]
      (when-not (= :ok (:status result))
        (throw (ex-info "fixture units failed to publish" {:result result})))
      (let [{:keys [ast warnings]}
@@ -167,11 +170,11 @@
                                 (cond-> (merge {:prelude :linked,
                                                 :path main,
                                                 :paths (when (:computed? opts) (keys (:addresses result)))}
-                                               (dissoc opts :computed? :src :request))
+                                               (dissoc opts :computed? :src :request :runtime))
                                   (:class-map opts) (assoc :class-map true)
                                   (some? (:request opts)) (assoc :request-node
                                                                  (portable/request-node (:request opts)))))
-           src (or (:src opts) (source (name-env result) result))
+           src (or (:src opts) (source runtime (name-env result) result))
            sites (:set (siteset/site-set-of ast))
            tree (vm/ast->semantic-bytecode ast)
            ast (u/seq-nodes [(core-module/require-node 'yang.call)
```

## test/yang/php/antlr/dart_linked_test.cljc
```clojure
     1	(ns yang.php.antlr.dart-linked-test
     2	  "Parser-generated P6 packets exercise the linked composition on Dart."
     3	  (:require [clojure.test :refer [deftest is testing]]
     4	            [yang.php.antlr.dart-linked-packets :as packets]
     5	            [yang.php.antlr.linked-harness :as linked]
     6	            [yang.php.antlr.lower :as lower]
     7	            [yang.php.antlr.siteset :as siteset]))
     8	
     9	(defn observed [value]
    10	  [(:php/out value)
    11	   (mapv (juxt :errno :message) (:php/diag value))
    12	   (some-> (:php/uncaught value) (select-keys [:class :message]))])
    13	
    14	(deftest linked-fixture-rows
    15	  (doseq [{:keys [id group packets out diag uncaught status]} packets/rows]
    16	    (testing (name id)
    17	      (when (#{:inc-mutual-once :inc-mutual-active} id)
    18	        (let [specs (lower/scc-module-specs
    19	                      (linked/registry)
    20	                      (mapv (fn [path] {:name path :packet (get packets path)})
    21	                            ["a.php" "b.php"])
    22	                      (:address @linked/published) {})]
    23	          (is (= (if (= id :inc-mutual-active) 6 0)
    24	                 (count (:set (siteset/site-set-of (:ast (first specs)))))))))
    25	      (let [result (linked/run-fixture packets "main.php"
    26	                                       (cond-> {} (= :dynscope-v1 group)
    27	                                         (assoc :runtime (linked/make-published))))]
    28	        (is (= (set (keys linked/backends)) (set (keys (:values result)))) (str id))
    29	        (doseq [[backend value] (:values result)]
    30	          (println "dart-linked-row" id backend (pr-str (observed value))
    31	                   "status" (get value :php/status 0) "fatal" (pr-str (:php/fatal value)))
    32	          (is (= [out (vec diag) uncaught] (observed value)) (str id " " backend))
    33	          (is (= (or status 0) (get value :php/status 0)) (str id " " backend))
    34	          (is (nil? (:php/fatal value)) (str id " " backend)))))))
```

## src/dev/yang/php/dart_linked_packets.clj
```clojure
     1	;; Regenerate after `bb gen:antlr --grammar php --target java`:
     2	;; clojure -Sdeps '{:aliases {:dartleg {:extra-paths ["test"]}}}' -M:dartleg src/dev/yang/php/dart_linked_packets.clj
     3	;; Packets retain the pinned parser output; the Dart test never parses source.
     4	(require '[yang.php.antlr.cst :as cst] '[yang.php.antlr.programs-p6 :as p6] '[clojure.string :as str])
     5	(def ids #{:inc-mutual-once :const-declare-and-read :const-redeclare-warns :const-undefined-error :const-define-redefinition})
     6	(def extras [[:uncaught "<?php echo NOPE; echo 'after';" "" [] {:class "Error" :message "Undefined constant \"NOPE\""} 255]
     7	 [:caught-finally "<?php try { echo NOPE; } catch (Error $e) { echo 'caught'; } finally { echo 'finally'; }" "caughtfinally" [] nil 0]
     8	 [:values "<?php define('X', 7); echo X; define('F', false); define('Z', 0); define('N', null); var_dump(F, Z, N);" "7bool(false)\nint(0)\nNULL\n" [] nil 0]
     9	 [:read-before-declaration "<?php echo LATER; const LATER = 1;" "" [] {:class "Error" :message "Undefined constant \"LATER\""} 255]
    10	 [:fresh-request "<?php echo X;" "" [] {:class "Error" :message "Undefined constant \"X\""} 255]])
    11	(def fixtures (filter #(or (ids (:id %)) (= :dynscope-v1 (:group %))) p6/enabled))
    12	(def base (first (filter #(= :inc-mutual-once (:id %)) fixtures)))
    13	(def active (-> base (assoc :id :inc-mutual-active) (update :units #(reduce (fn [m p] (update m p str/replace-first "<?php" "<?php declare(ticks=1);")) % ["a.php" "b.php"]))))
    14	(defn packets [fixture] (let [units (or (:units fixture) {"main.php" (:source fixture)})] (-> fixture (dissoc :source :units) (assoc :packets (update-vals units cst/parse-source)))))
    15	(def rows (mapv packets (concat fixtures [active] (map (fn [[id source out diag uncaught status]] {:id id :source source :out out :diag diag :uncaught uncaught :status status}) extras))))
    16	(spit "test/yang/php/antlr/dart_linked_packets.cljc" (str ";; Generated from programs-p6 and constant-admission sources using the pinned PHP parser.\n(ns yang.php.antlr.dart-linked-packets)\n\n(def rows\n  '" (pr-str rows) ")\n"))
    17	(println (mapv :id rows))
```

## /tmp/dartleg-r2-agg.out excerpt (lines 1225-1235 and 5150-5197)
```text
  1225	00:30 +804: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_0_test.dart: null yang.python.antlr.int-literal-test/integral-float-is-not-an-int-test
  1226	00:30 +805: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_0_test.dart: null yang.python.antlr.int-literal-test/integral-float-is-not-an-int-test
  1227	00:30 +806: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_0_test.dart: null yang.python.antlr.int-literal-test/integral-float-is-not-an-int-test
  1228	00:30 +807: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_0_test.dart: null yang.python.antlr.int-literal-test/integral-float-is-not-an-int-test
  1229	00:30 +808: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_0_test.dart: null yang.python.antlr.int-literal-test/integral-float-is-not-an-int-test
  1230	00:30 +809: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  1231	00:30 +810: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  1232	00:30 +811: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  1233	00:30 +812: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  1234	00:30 +813: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  1235	00:30 +814: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5150	12:55 +4302: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5151	12:55 +4303: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5152	12:56 +4304: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5153	12:56 +4305: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5154	12:56 +4306: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows
  5155	dart-linked-row :inc-mutual-once :register ["ab" [] nil] status 0 fatal nil
  5156	dart-linked-row :inc-mutual-once :stack ["ab" [] nil] status 0 fatal nil
  5157	dart-linked-row :inc-mutual-once :semantic ["ab" [] nil] status 0 fatal nil
  5158	dart-linked-row :dyn-shares-locals :register ["11y" [] nil] status 0 fatal nil
  5159	dart-linked-row :dyn-shares-locals :stack ["11y" [] nil] status 0 fatal nil
  5160	dart-linked-row :dyn-shares-locals :semantic ["11y" [] nil] status 0 fatal nil
  5161	dart-linked-row :dyn-does-not-touch-globals :register ["changedg" [] nil] status 0 fatal nil
  5162	dart-linked-row :dyn-does-not-touch-globals :stack ["changedg" [] nil] status 0 fatal nil
  5163	dart-linked-row :dyn-does-not-touch-globals :semantic ["changedg" [] nil] status 0 fatal nil
  5164	177:14 +4306: /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows - did not complete [E]
  5165	177:14 +4306: Some tests failed.
  5166	
  5167	Failing tests:
  5168	  /home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart: null yang.php.antlr.dart-linked-test/linked-fixture-rows (did not complete)
  5169	unhandled error during finalization of test:
  5170	/home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart
  5171	TestDeviceException(Shell subprocess crashed with SIGTERM (-15).)
  5172	#0      FlutterTesterTestDevice.finished (package:flutter_tools/src/test/flutter_tester_device.dart:256:5)
  5173	<asynchronous suspension>
  5174	#1      FlutterTesterTestDevice.kill (package:flutter_tools/src/test/flutter_tester_device.dart:238:5)
  5175	<asynchronous suspension>
  5176	#2      FlutterPlatform._startTest.<anonymous closure> (package:flutter_tools/src/test/flutter_platform.dart:689:9)
  5177	<asynchronous suspension>
  5178	#3      FlutterPlatform._startTest.finalize (package:flutter_tools/src/test/flutter_platform.dart:591:11)
  5179	<asynchronous suspension>
  5180	#4      asyncGuard.<anonymous closure> (package:flutter_tools/src/base/async_guard.dart:108:24)
  5181	<asynchronous suspension>
  5182	
  5183	unhandled error during finalization of test:
  5184	/home/sto/ci/dw-dartleg/build/cljd-agg/shard_1_test.dart
  5185	PathNotFoundException: Deletion failed, path = '/tmp/flutter_tools.KSBSPN/flutter_test_listener.WSBASM' (OS Error: No such file or directory, errno = 2)
  5186	#0      _Directory._deleteSync (dart:io/directory_impl.dart:216:7)
  5187	#1      FileSystemEntity.deleteSync (dart:io/file_system_entity.dart:425:7)
  5188	#2      ForwardingFileSystemEntity.deleteSync (package:file/src/forwarding/forwarding_file_system_entity.dart:70:16)
  5189	#3      ErrorHandlingDirectory.deleteSync.<anonymous closure> (package:flutter_tools/src/base/error_handling_io.dart:717:22)
  5190	#4      _runSync (package:flutter_tools/src/base/error_handling_io.dart:1118:16)
  5191	#5      ErrorHandlingDirectory.deleteSync (package:flutter_tools/src/base/error_handling_io.dart:716:12)
  5192	#6      FlutterPlatform._createListenerDart.<anonymous closure> (package:flutter_tools/src/test/flutter_platform.dart:786:15)
  5193	#7      FlutterPlatform._startTest.finalize (package:flutter_tools/src/test/flutter_platform.dart:591:26)
  5194	<asynchronous suspension>
  5195	#8      asyncGuard.<anonymous closure> (package:flutter_tools/src/base/async_guard.dart:108:24)
  5196	<asynchronous suspension>
  5197	
```
