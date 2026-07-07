(ns dao.space.query-test
  "Contract tests for dao.space.query: the pure, stateless match/q library
  specified in docs/design/dao.space.md. Exercises the four source shapes
  the design doc's Source Polymorphism section requires — a single
  dao.jing handle, a collection of dao.jing handles (federated query,
  justified by ADR 0001's monoid homomorphism), a raw vector of datoms,
  and a raw vector of entity maps — plus mixes of them."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as file]
            [dao.space.query :as query]))


;; ---------------------------------------------------------------------------
;; match / q over a raw vector of datoms
;; ---------------------------------------------------------------------------

(def sample-datoms
  [[1 :work/status :todo 0 1] [1 :work/task "write tests" 0 1]
   [2 :work/status :done 0 1] [2 :work/task "ship it" 0 1]])


(deftest match-over-raw-datoms
  (testing "a positional template with _ wildcards returns matching datoms"
    (is (= #{[1 :work/status :todo 0 1] [2 :work/status :done 0 1]}
           (set (query/match sample-datoms ['_ :work/status '_]))))
    (is (= #{[1 :work/status :todo 0 1]}
           (set (query/match sample-datoms [1 :work/status '_]))))
    (is (= [] (query/match sample-datoms [99 '_ '_])))))


(deftest q-over-raw-datoms
  (testing "find/where Datalog over a plain datom vector"
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                    sample-datoms)))
    (is (= #{[1]}
           (query/q '[:find ?id :where [?id :work/status :todo]
                      [?id :work/task ?task]]
                    sample-datoms)))))


(deftest q-single-clause-is-match
  (testing "a single-clause q agrees with match, per dao.space.md's match ⊂ q"
    (is (= #{[1] [2]}
           (query/q '[:find ?id :where [?id :work/status _]] sample-datoms)))))


;; ---------------------------------------------------------------------------
;; match / q over a raw vector of entity maps
;; ---------------------------------------------------------------------------

(deftest entity-maps-normalize-to-datoms
  (testing "each map becomes one datom per k/v pair; no :db/id gets a fresh id"
    (let [source [{:work/status :todo, :work/task "a"}
                  {:work/status :done, :work/task "b"}]]
      (is (= #{["a"] ["b"]}
             (query/q '[:find ?task :where [_ :work/task ?task]] source)))))
  (testing "an explicit :db/id is used verbatim and joins across maps"
    (let [source [{:db/id :e1, :work/status :todo}
                  {:db/id :e1, :work/task "a"}]]
      (is (= #{[:e1 "a"]}
             (query/q '[:find ?id ?task :where [?id :work/status :todo]
                        [?id :work/task ?task]]
                      source)))))
  (testing "two maps without :db/id get distinct synthetic ids"
    (let [source [{:work/status :todo} {:work/status :todo}]
          ids (query/q '[:find ?id :where [?id :work/status :todo]] source)]
      (is (= 2 (count ids)))
      (is (apply not= (map first ids))))))


(deftest match-over-entity-maps
  (let [source [{:work/status :todo, :work/task "a"}]]
    (is (= 2 (count (query/match source ['_ '_ '_]))))
    (is (= 1 (count (query/match source ['_ :work/status :todo]))))))


;; ---------------------------------------------------------------------------
;; match / q over a single dao.jing handle
;; ---------------------------------------------------------------------------

(defn- seed!
  "Write datoms into a dao.jing handle under dao.space.query's read
  convention, as a stream owner would (dao.space.query itself never
  writes; see its ns docstring)."
  [store datoms]
  (jing/cas! store query/default-datoms-key 0 {:datoms datoms}))


(deftest q-over-a-single-dao-jing-handle
  (testing "a dao.jing handle is read through the :root/datoms convention"
    (let [store (jing/create-kv-mem)]
      (seed! store sample-datoms)
      (is (= #{[1 "write tests"] [2 "ship it"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      store))))))


(deftest match-over-a-single-dao-jing-handle
  (let [store (jing/create-kv-mem)]
    (seed! store sample-datoms)
    (is (= 1 (count (query/match store [1 :work/status '_]))))))


(deftest empty-dao-jing-handle-yields-no-datoms
  (testing "an unseeded store contributes nothing, not an error"
    (let [store (jing/create-kv-mem)]
      (is (= [] (query/match store ['_ '_ '_])))
      (is (= #{} (query/q '[:find ?e :where [?e _ _]] store))))))


;; ---------------------------------------------------------------------------
;; match / q over MULTIPLE dao.jing handles (federated)
;; ---------------------------------------------------------------------------

(deftest q-over-multiple-dao-jing-handles
  (testing
    "a collection of stores folds and merges, per ADR 0001's monoid
           homomorphism: index(S1 ⊎ S2) = merge(index(S1), index(S2))"
    (let [a (jing/create-kv-mem)
          b (jing/create-kv-mem)]
      (seed! a [[1 :work/status :todo 0 1] [1 :work/task "a" 0 1]])
      (seed! b [[2 :work/status :done 0 1] [2 :work/task "b" 0 1]])
      (is (= #{[1 "a"] [2 "b"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]] [a b])))
      (testing "querying each store alone yields a strict subset"
        (is (= #{[1 "a"]}
               (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                        a)))))))


(deftest match-over-multiple-dao-jing-handles
  (let [a (jing/create-kv-mem)
        b (jing/create-kv-mem)]
    (seed! a [[1 :work/status :todo 0 1]])
    (seed! b [[2 :work/status :todo 0 1]])
    (is (= #{[1 :work/status :todo 0 1] [2 :work/status :todo 0 1]}
           (set (query/match [a b] ['_ :work/status :todo]))))))


;; ---------------------------------------------------------------------------
;; Mixed sources
;; ---------------------------------------------------------------------------

(deftest mixed-source-collection
  (testing "a dao.jing handle and a raw datom vector fold together"
    (let [store (jing/create-kv-mem)]
      (seed! store [[1 :work/status :todo 0 1]])
      (is (= #{[1] [2]}
             (query/q '[:find ?id :where [?id :work/status :todo]]
                      [store [[2 :work/status :todo 0 1]]]))))))


;; ---------------------------------------------------------------------------
;; as-of
;; ---------------------------------------------------------------------------

(deftest as-of-bounds-visible-datoms
  (testing "as-of excludes datoms with t greater than the bound"
    (let [datoms [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]]]
      (is (=
            #{[:todo]}
            (query/q '[:find ?v :where [1 :work/status ?v]] datoms {:as-of 0})))
      (is (= #{[:todo] [:done]}
             (query/q '[:find ?v :where [1 :work/status ?v]]
                      datoms
                      {:as-of 5}))))))


(deftest match-respects-as-of
  (let [datoms [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]]]
    (is (= 1 (count (query/match datoms [1 :work/status '_] {:as-of 0}))))
    (is (= 2 (count (query/match datoms [1 :work/status '_] {:as-of 5}))))))


;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(deftest unrecognized-source-throws
  (is (thrown? #?(:cljs js/Error
                  :cljd Object
                  :default Exception)
        (query/q '[:find ?e :where [?e _ _]] 42))))


;; ---------------------------------------------------------------------------
;; Cross-backend: dao.jing.file (persistent, cross-platform)
;; ---------------------------------------------------------------------------

(deftest q-over-a-file-backed-dao-jing-handle
  (testing
    "the query library is backend-agnostic: KVFile works exactly like KVMem"
    (let [path (str "target/query-test-" (random-uuid) ".db")
          store (file/create-kv-file path)]
      (try (seed! store sample-datoms)
           (is (= #{[1 "write tests"] [2 "ship it"]}
                  (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                           store)))
           (finally (jing/close! store)
                    #?(:clj (.delete (java.io.File. path))
                       :cljs (.unlinkSync (js/require "fs") path)
                       :cljd nil))))))
