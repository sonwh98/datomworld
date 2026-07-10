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


;; ---------------------------------------------------------------------------
;; Target Architecture: owner-built, lazily-pulled indexes (publish-index!)
;; ---------------------------------------------------------------------------
;; publish-index! is JVM-only for now (psset durability is a Clojure-only
;; feature), so the publish-side tests are :clj-guarded — with :cljd FIRST
;; in the conditionals, since the cljd host pass also matches :clj. The
;; hand-built node-graph test at the end is unguarded: node blobs are plain
;; EDN, readable on every platform.

#?(:cljd nil
   :clj (def ^:private many-datoms
          "Enough datoms to force a multi-node tree at :branching-factor 32."
          (vec (for [i (range 1025 1625)] [i :work/task (str "task-" i) 0 1]))))


#?(:cljd nil
   :clj
   (defn- recording-store
     "Wrap an IKVStore, recording every get key into the log atom."
     [inner log]
     (reify
       jing/IKVStore
       (put! [_ k v] (jing/put! inner k v))

       (cas! [_ k old-rev v] (jing/cas! inner k old-rev v))

       (get [_ k not-found] (swap! log conj k) (jing/get inner k not-found))

       (delete! [_ k] (jing/delete! inner k))

       (close! [_] (jing/close! inner)))))


#?(:cljd nil
   :clj
   (deftest publish-index-root-shape-and-parity
     (testing
       "publish advances the root to {:indexes ... :count n} and
              q/match answer identically before and after"
       (let [store (jing/create-kv-mem)
             _ (seed! store many-datoms)
             q-form '[:find ?v :where [1030 :work/task ?v]]
             before-q (query/q q-form store)
             before-m (query/match store [1030 :work/task '_])
             _ (query/publish-index! store)
             root (jing/get store query/default-datoms-key nil)]
         (is (= #{:eavt :aevt :avet} (set (keys (:indexes root)))))
         (is (every? #(= "segment" (namespace %)) (vals (:indexes root)))
             "index roots are content-addressed segment keys")
         (is (= (count many-datoms) (:count root)))
         (is (nil? (:datoms root)) "the wholesale datom vector is gone")
         (is (= before-q (query/q q-form store)))
         (is (= before-m (query/match store [1030 :work/task '_])))))))


#?(:cljd nil
   :clj
   (deftest publish-index-republish-is-idempotent
     (testing
       "content addressing: unchanged data yields identical root
              addresses on republish"
       (let [store (jing/create-kv-mem)
             _ (seed! store many-datoms)
             idx1 (query/publish-index! store)
             idx2 (query/publish-index! store (query/read-datoms store))]
         (is (= idx1 idx2))))))


#?(:cljd nil
   :clj
   (deftest publish-index-lazy-fetch
     (testing
       "a bound-e match over a published index loads only the seek
              path, not the whole tree"
       (let [inner (jing/create-kv-mem)
             gets (atom [])
             store (recording-store inner gets)
             _ (seed! store many-datoms)
             _ (query/publish-index! store many-datoms {:branching-factor 32})
             total-segments (count (filter #(= "segment" (namespace %))
                                           (keys @(:state-atom inner))))
             _ (reset! gets [])
             result (query/match store [1300 :work/task '_])
             segment-gets (count (filter #(= "segment" (namespace %)) @gets))]
         (is (= [[1300 :work/task "task-1300" 0 1]] result))
         (is (> total-segments 15) "the tree really is multi-node")
         (is (< segment-gets 6)
             (str "a point lookup should load only root+path, got "
                  segment-gets
                  " of " total-segments))))))


#?(:cljd nil
   :clj
   (deftest published-index-supports-as-of-and-federation
     (testing
       "as-of and federated queries fall back to the eager walk and
              stay correct over an indexed root"
       (let [a (jing/create-kv-mem)
             b (jing/create-kv-mem)]
         (seed! a [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]])
         (query/publish-index! a)
         (seed! b [[2 :work/status :todo 0 1]])
         (is (=
               #{[:todo]}
               (query/q '[:find ?v :where [1 :work/status ?v]] a {:as-of 0})))
         (is (= #{[1] [2]}
                (query/q '[:find ?e :where [?e :work/status :todo]]
                         [a b])))))))


#?(:cljd nil
   :clj
   (deftest publish-index-of-nothing-is-readable
     (testing
       "publishing an empty stream yields nil index roots that read
              back as no datoms, not an error"
       (let [store (jing/create-kv-mem)]
         (query/publish-index! store [])
         (is (= [] (query/match store ['_ '_ '_])))
         (is (= #{} (query/q '[:find ?e :where [?e _ _]] store)))))))


(deftest index-root-readable-from-plain-node-blobs
  (testing
    "a hand-built node graph — plain EDN blobs, no psset involved in
           its construction — answers q/match on every platform (lazily
           restored on the JVM, eagerly walked elsewhere)"
    (let [store (jing/create-kv-mem)
          leaf-1 {:keys [[1 :a "x" 0 1] [2 :a "y" 0 1]]}
          k1 (jing/segment-key leaf-1)
          leaf-2 {:keys [[3 :b "z" 0 1]]}
          k2 (jing/segment-key leaf-2)
          branch {:level 1,
                  :keys [[2 :a "y" 0 1] [3 :b "z" 0 1]],
                  :addresses [k1 k2]}
          kb (jing/segment-key branch)]
      (jing/put! store k1 leaf-1)
      (jing/put! store k2 leaf-2)
      (jing/put! store kb branch)
      (jing/cas! store
                 query/default-datoms-key
                 0
                 {:indexes {:eavt kb, :aevt kb, :avet kb}, :count 3})
      (is (= #{["x"]} (query/q '[:find ?v :where [1 :a ?v]] store)))
      (is (= 3 (count (query/match store ['_ '_ '_])))))))


#?(:cljd (deftest publish-index-is-jvm-only
           (is (thrown? Object (query/publish-index! (jing/create-kv-mem) []))))
   :clj nil
   :cljs (deftest publish-index-is-jvm-only
           (is (thrown? js/Error
                 (query/publish-index! (jing/create-kv-mem) [])))))


;; ---------------------------------------------------------------------------
;; :in bindings
;; ---------------------------------------------------------------------------

(deftest in-bindings-test
  (testing "scalar binding"
    (is (= #{[1]}
           (query/q '[:find ?e :in $ ?name :where [?e :name ?name]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]]
                    "Alice"))))
  (testing "collection binding"
    (is (= #{["Alice"] ["Bob"]}
           (query/q '[:find ?name :in $ [?id ...] :where [?id :name ?name]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]
                     [3 :name "Charlie" 0 1]]
                    [1 2]))))
  (testing "tuple binding"
    (is (= #{[1]}
           (query/q '[:find ?e :in $ [?name ?age] :where [?e :name ?name]
                      [?e :age ?age]]
                    [[1 :name "Alice" 0 1] [1 :age 30 0 1] [2 :name "Bob" 0 1]
                     [2 :age 30 0 1]]
                    ["Alice" 30]))))
  (testing "relation binding"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :in $ [[?name ?age]] :where [?e :name ?name]
                      [?e :age ?age]]
                    [[1 :name "Alice" 0 1] [1 :age 30 0 1] [2 :name "Bob" 0 1]
                     [2 :age 40 0 1] [3 :name "Charlie" 0 1] [3 :age 50 0 1]]
                    [["Alice" 30] ["Bob" 40]]))))
  (testing "multiple db sources"
    (is (= #{[1 "Alice" 30] [2 "Bob" 40]}
           (query/q '[:find ?e ?name ?age :in $a $b :where [$a ?e :name ?name]
                      [$b ?e :age ?age]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]]
                    [[1 :age 30 0 1] [2 :age 40 0 1]])))))


;; ---------------------------------------------------------------------------
;; current-state resolution
;; ---------------------------------------------------------------------------

(deftest current-state-resolution-test
  (testing "latest t supersedes older t, retracted facts are dropped"
    (let [datoms [[1 :color "red" 1 1]     ; asserted at t=1
                  [1 :color "red" 2 0]     ; retracted at t=2
                  [1 :color "blue" 2 1]    ; asserted at t=2
                  [2 :status "active" 1 1] ; asserted at t=1
                  [2 :status "active" 3 0]]]
      ;; For entity 1, "red" is retracted and "blue" is asserted
      (is (= #{["blue"]} (query/q '[:find ?c :where [1 :color ?c]] datoms)))
      ;; For entity 2, "active" is retracted entirely
      (is (= #{} (query/q '[:find ?s :where [2 :status ?s]] datoms)))
      ;; With as-of 1, "red" is still asserted and "active" is still
      ;; asserted
      (is (= #{["red"]}
             (query/q '[:find ?c :where [1 :color ?c]] datoms {:as-of 1})))
      (is (= #{["active"]}
             (query/q '[:find ?s :where [2 :status ?s]] datoms {:as-of 1}))))))


;; ---------------------------------------------------------------------------
;; entity-attrs
;; ---------------------------------------------------------------------------

(deftest entity-attrs-test
  (testing "returns a map of attributes for the entity"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [1 :hobby "reading" 1 1]
                  [1 :hobby "coding" 2 1]]]
      (is (= {:name "Alice", :age 30, :hobby ["coding" "reading"]}
             (query/entity-attrs datoms 1))))))
