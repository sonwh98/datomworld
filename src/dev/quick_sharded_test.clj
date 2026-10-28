(ns quick-sharded-test
  (:require
    [affected :as a]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [build-cache :as cache]
    [clojure.test :refer [deftest is testing run-tests]]
    [quick-sharded :as quick]
    [test-routed :as routed]))


(deftest exact-subsystem-coverage
  (let [cfg {:subsystems [{:name "vm" :prefixes ["yin.vm"] :layer 2}
                          {:name "ucf" :prefixes ["yin.vm.ucf"] :layer 2}]}
        nses '[yin.vm-test yin.vm.ucf.step-test new-test]
        index (mapv #(hash-map :ns % :test? true :path (str "test/" % ".clj")
                               :ext "clj") nses)
        shards (quick/shard-plan index cfg nses)]
    (is (= '{"vm" [yin.vm-test] "ucf" [yin.vm.ucf.step-test]
             "unmapped" [new-test]} shards))
    (is (= {:selected 3 :covered 3 :uncovered [] :unexpected [] :duplicates []}
           (quick/coverage nses shards)))
    (is (= '[new-test] (:uncovered (quick/coverage nses (dissoc shards "unmapped")))))
    (is (= '[yin.vm-test] (:duplicates (quick/coverage nses (assoc shards "dup" '[yin.vm-test])))))
    (is (= '[extra-test] (:unexpected (quick/coverage nses (assoc shards "extra" '[extra-test])))))
    (let [out (str (fs/create-temp-dir {:dir "target" :prefix "coverage-self-"}))]
      (try
        (is (thrown-with-msg? Exception #"FAIL: shard coverage mismatch"
              (quick/check-coverage! nses (dissoc shards "unmapped") out)))
        (is (= "new-test\n" (slurp (str out "/uncovered.txt"))))
        (finally (fs/delete-tree out))))))


(def green "Ran 12 tests containing 34 assertions.\n0 failures, 0 errors.\n")


(deftest summaries-fail-closed
  (is (= {:tests 12 :assertions 34 :failures 0 :errors 0} (quick/jvm-summary green)))
  (is (nil? (quick/jvm-summary "Testing broken-test\n")))
  (let [out (str (fs/create-temp-dir {:dir "target" :prefix "shard-self-"}))]
    (try
      (doseq [[text exit pass?] [[green 0 true] [green 7 false] ["no summary" 0 false]
                                 ["Ran 1 tests containing 1 assertions.\n0 failures, 1 errors.\n" 0 false]
                                 ["Ran 1 tests containing 1 assertions.\n1 failures, 0 errors.\n" 0 false]]]
        (with-redefs [p/process (fn [_ opts]
                                  (spit (:out opts) text)
                                  (delay {:exit exit}))]
          (is (= pass? (:pass? (quick/run-shard! out ["probe" '[probe-test]]))))))
      (finally (fs/delete-tree out)))))


(deftest bounded-worker-count
  (let [active (atom 0) peak (atom 0)]
    (with-redefs [quick/run-shard! (fn [_ [shard _]]
                                     (let [n (swap! active inc)] (swap! peak max n))
                                     (Thread/sleep 20)
                                     (swap! active dec)
                                     {:shard shard :pass? true})]
      (is (= 9 (count (quick/run-shards! "unused" (zipmap (range 9) (repeat '[x])) 3))))
      (is (<= @peak 3))
      (is (zero? @active)))))


(deftest routing-policy
  (let [cfg (a/load-subsystems-config)
        index [(a/index-entry "src/cljc/dao/await.cljc" "(ns dao.await)")
               (a/index-entry "src/cljc/yang/clojure.cljc" "(ns yang.clojure)")
               (a/index-entry "src/cljc/dao/gui/foo.cljc" "(ns dao.gui.foo)")
               (a/index-entry "test/dao/gui/foo_test.cljc" "(ns dao.gui.foo-test (:require [dao.gui.foo])) (deftest works)")]]
    (doseq [[paths class] [[["docs/agents/build-n-test.md"] :docs]
                           [["deps.edn"] :toolchain]
                           [["bb.edn" "docs/readme.md"] :toolchain]
                           [["src/dev/affected.clj"] :toolchain]
                           [["antlr/python3/Python3Parser.g4"] :toolchain]
                           [["jolt-toolchain.edn"] :toolchain]
                           [["src/cljc/dao/await.cljc"] :foundation]
                           [["src/cljc/yang/clojure.cljc"] :foundation]
                           [["src/cljc/yang/python.cljc"] :foundation]
                           [["src/cljc/yang/typescript.cljc"] :foundation]
                           [["src/cljc/yang/php.cljc"] :foundation]
                           [["src/cljc/dao/gui/foo.cljc"] :leaf]
                           [["test/resources/example.md"] :unselected]
                           [["unknown.runtime"] :unselected]]]
      (is (= class (:class (routed/route index cfg paths))) (pr-str paths))))
  (testing "leaf exit 2 dispatches full gate and propagates its failure"
    (let [commands (atom [])]
      (with-redefs [p/process (fn [cmd _]
                                (swap! commands conj cmd)
                                (delay {:exit (if (= ["bb" "test"] cmd) 9 2)}))]
        (is (= 9 (routed/dispatch! {:class :leaf :command ["bb" "test:changed"]} false)))
        (is (= [["bb" "test:changed"] ["bb" "test"]] @commands)))))
  (testing "dry dispatch never spawns anything"
    (with-redefs [p/process (fn [& _] (throw (ex-info "must not execute" {})))]
      (is (zero? (routed/dispatch! {:class :toolchain :command ["bb" "test"]} true))))))


(deftest content-cache-invalidation
  (fs/create-dirs "target")
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "cache-self-"}))
        id (str "self-" (random-uuid))
        output (str dir "/artifact")
        counter (str dir "/runs")
        command ["sh" "-c" "printf generated > \"$1\"; printf run >> \"$2\""
                 "cache-probe" output counter]
        run #(cache/cached! id % [output] command)]
    (try
      (is (zero? (run "input-1")))
      (is (zero? (run "input-1")))
      (is (= "run" (slurp counter)))
      (is (zero? (run "input-2")))
      (is (= "runrun" (slurp counter)))
      (spit output "corrupt")
      (is (zero? (run "input-2")))
      (is (= "runrunrun" (slurp counter)))
      (fs/delete output)
      (is (zero? (run "input-2")))
      (is (= "runrunrunrun" (slurp counter)))
      (is (= 7 (cache/cached! id "new-input" [output] ["sh" "-c" "exit 7"])))
      (is (not (fs/exists? (str "target/prereq-cache/" id ".edn"))))
      (is (thrown? Exception (cache/local-path! "/tmp/shared-output")))
      (fs/create-dir (str "target/prereq-cache/" id ".lock"))
      (is (thrown-with-msg? Exception #"cache is locked"
            (run "input-2")))
      (fs/delete (str "target/prereq-cache/" id ".lock"))
      (finally
        (fs/delete-tree dir)
        (fs/delete-if-exists (str "target/prereq-cache/" id ".edn"))))))


(defn -main
  [& _]
  (let [{:keys [fail error]} (run-tests 'quick-sharded-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
