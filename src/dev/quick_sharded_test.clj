(ns quick-sharded-test
  (:require
    [affected :as a]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [build-cache :as cache]
    [clojure.edn :as edn]
    [clojure.string :as str]
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


(defn scope-records
  [output]
  (mapv edn/read-string
        (filter #(str/starts-with? % "{:type :verification-scope")
                (str/split-lines output))))


(deftest docs-checks-staged-and-untracked
  (let [dir (str (fs/create-temp-dir {:prefix "routed-docs-"}))
        process p/process
        git! (fn [& args]
               (let [result @(process (into ["git"] args) {:dir dir :out :string :err :string})]
                 (is (zero? (:exit result)) (:err result))))
        run (fn [paths]
              (let [exit (atom nil)
                    output (with-out-str
                             (with-redefs [p/process (fn [cmd opts]
                                                       (process cmd (assoc opts :dir dir)))]
                               (reset! exit (routed/dispatch! (routed/route [] {} paths) false))))]
                [@exit output (last (scope-records output))]))]
    (try
      (git! "init" "-q")
      (spit (str dir "/tracked.md") "clean\n")
      (git! "add" "tracked.md")
      (git! "-c" "user.name=Probe" "-c" "user.email=probe@example.invalid"
            "-c" "commit.gpgsign=false" "commit" "-qm" "probe")
      (testing "fully staged whitespace errors must fail"
        (spit (str dir "/tracked.md") "bad trailing whitespace   \n")
        (git! "add" "tracked.md")
        (is (pos? (first (run ["tracked.md"]))))
        (spit (str dir "/tracked.md") "clean\n"))
      (testing "HEAD-to-working-tree check uses staged and unstaged content together"
        (let [[exit _ scope] (run ["tracked.md"])]
          (is (zero? exit))
          (is (true? (:gate-complete? scope)))))
      (testing "untracked docs are explicitly unchecked, including mixed sets"
        (spit (str dir "/new doc.md") "untracked   \n")
        (doseq [paths [["new doc.md"] ["tracked.md" "new doc.md"]]]
          (let [[exit output scope] (run paths)]
            (is (zero? exit))
            (is (str/includes? output "new doc.md: unchecked (untracked)"))
            (is (= ["new doc.md"] (:unchecked scope)))
            (is (false? (:gate-complete? scope))))))
      (finally (fs/delete-tree dir)))))


(deftest routed-verification-scope
  (doseq [[class exit dry? complete? scope]
          [[:foundation 0 false false :jvm-feedback]
           [:foundation 7 false false :jvm-feedback]
           [:toolchain 0 false true :full-quick-tri-host]
           [:leaf 0 false true :selected-quick-tri-host]
           [:toolchain 0 true false :full-quick-tri-host]
           [:toolchain 7 false false :full-quick-tri-host]]]
    (let [output (with-out-str
                   (with-redefs [p/process (fn [& _]
                                             (is (not dry?))
                                             (delay {:exit exit}))]
                     (is (= exit (routed/dispatch! {:class class :command ["probe"]} dry?)))))
          record (last (scope-records output))]
      (is (= scope (:scope record)))
      (is (= complete? (:gate-complete? record)))
      (is (= dry? (:dry-run? record)))
      (is (= exit (:exit record)))))
  (testing "failed process launch still emits an incomplete scope"
    (let [output (with-out-str
                   (with-redefs [p/process (fn [& _] (throw (ex-info "launch failed" {})))]
                     (is (thrown-with-msg? Exception #"launch failed"
                           (routed/dispatch! {:class :toolchain :command ["probe"]} false)))))]
      (is (false? (:gate-complete? (last (scope-records output)))))
      (is (= 1 (:exit (last (scope-records output))))))))


(deftest quick-verification-scope
  (fs/create-dirs "target")
  (doseq [[nses list? pass? exit] [['[probe-test] false true 0]
                                   ['[probe-test] false false 1]
                                   ['[probe-test] true true 0]
                                   [[] false true 1]
                                   [[] true true 1]]]
    (let [out (atom nil)
          runs (atom 0)]
      (try
        (let [output (with-out-str
                       (with-redefs [a/scan-index (constantly [])
                                     a/load-subsystems-config (constantly {:subsystems []})
                                     a/lane-tests (fn [& _] nses)
                                     a/prerequisites (fn [& _] #{})
                                     quick/check-coverage! (let [check quick/check-coverage!]
                                                             (fn [selected shards dir]
                                                               (reset! out dir)
                                                               (check selected shards dir)))
                                     quick/run-shards! (fn [& _]
                                                         (swap! runs inc)
                                                         [{:tests 1 :assertions 1 :failures (if pass? 0 1)
                                                           :errors 0 :pass? pass?}])]
                         (is (= exit (quick/run-quick! {:workers 1 :list? list?})))))
              record (last (scope-records output))]
          (is (= :jvm-feedback (:scope record)))
          (is (false? (:gate-complete? record)))
          (is (= (count nses) (:selected record)))
          (is (= exit (:exit record)))
          (is (= list? (:dry-run? record)))
          (is (= (if (or list? (empty? nses)) 0 1) @runs))
          (when (empty? nses)
            (is (str/includes? output "0 JVM namespaces selected")))
          (when (and (seq nses) (not list?))
            (is (= record (:verification-scope (edn/read-string (slurp (str @out "/summary.edn"))))))))
        (finally (when @out (fs/delete-tree @out)))))))


(deftest quick-prerequisite-failure-scope
  (let [out (atom nil)]
    (try
      (let [output (with-out-str
                     (with-redefs [a/scan-index (constantly [])
                                   a/load-subsystems-config (constantly {:subsystems []})
                                   a/lane-tests (fn [& _] '[probe-test])
                                   a/prerequisites (fn [& _] #{:gen:antlr})
                                   quick/check-coverage! (let [check quick/check-coverage!]
                                                           (fn [selected shards dir]
                                                             (reset! out dir)
                                                             (check selected shards dir)))
                                   p/process (fn [& _] (delay {:exit 7}))
                                   quick/run-shards! (fn [& _] (throw (ex-info "must not run" {})))]
                       (is (thrown-with-msg? Exception #"prerequisite failed: gen:antlr"
                             (quick/run-quick! {:workers 1 :list? false})))))]
        (is (= [{:type :verification-scope :runner :quick-sharded :scope :jvm-feedback
                 :exit 1 :dry-run? false :gate-complete? false}]
               (scope-records output))))
      (finally (when @out (fs/delete-tree @out))))))


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
