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
            [dao.space.index :as index]
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
  (jing/cas! store index/default-datoms-key 0 {:datoms datoms}))


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
    (is (= 1 (count (query/match store [1 :work/status '_]))))
    (is
      (= [[1 :work/status :todo 0 1]] (query/match store ['_ '_ :todo]))
      "v-only match exercises the in-memory VAET built from the
        wholesale root's :datoms")))


;; A v-only match against a wholesale root only exercises the in-memory
;; VAET that index-datoms builds. This test publishes first so the
;; v-only branch reaches the lazy restored VAET (fold's complete-manifest
;; guard) — the only way to prove the persisted-VAET path works.
#?(:cljd nil
   :clj (deftest v-only-match-reaches-the-published-vaet
          (let [store (jing/create-kv-mem)]
            (seed! store sample-datoms)
            (index/publish-index! store)
            (is (= #{[1 :work/status :todo 0 1]}
                   (set (query/match store ['_ '_ :todo]))))
            (is (= 1
                   (count (query/q '[:find ?e :where [?e :work/status :todo]]
                                   store)))))))


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
;; Querying over owner-built indexed roots
;; ---------------------------------------------------------------------------
;; The index realization itself (publish-index!, node-blob format, laziness)
;; is dao.space.index's contract, tested in dao.space.index-test; here only
;; the query-side behaviors over a published root.

#?(:cljd nil
   :clj
   (deftest published-index-supports-as-of-and-federation
     (testing
       "as-of and federated queries fall back to the eager walk and
              stay correct over an indexed root"
       (let [a (jing/create-kv-mem)
             b (jing/create-kv-mem)]
         (seed! a [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]])
         (index/publish-index! a)
         (seed! b [[2 :work/status :todo 0 1]])
         (is (=
               #{[:todo]}
               (query/q '[:find ?v :where [1 :work/status ?v]] a {:as-of 0})))
         (is (= #{[1] [2]}
                (query/q '[:find ?e :where [?e :work/status :todo]]
                         [a b])))))))


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


;; ---------------------------------------------------------------------------
;; Negation (not / not-join)
;; ---------------------------------------------------------------------------

(deftest negation-test
  (testing "not clause filters out bindings that satisfy the inner clauses"
    (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]
                  [2 :work/posted true 1 1] [2 :work/task "Buy groceries" 1 1]
                  [2 :work/claims "user1" 1 1]]]
      (is (= #{[1 "Clean room"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        [?w :work/task ?task] (not [?w :work/claims _])]
                      datoms)))
      (testing
        "a retracted claim makes the item reappear (current-state interaction)"
        (let [datoms-retracted (conj datoms [2 :work/claims "user1" 2 0])]
          (is (= #{[1 "Clean room"] [2 "Buy groceries"]}
                 (query/q '[:find ?w ?task :where [?w :work/posted true]
                            [?w :work/task ?task] (not [?w :work/claims _])]
                          datoms-retracted)))))
      (testing
        "claims modeled as [claim-entity :work/claims ?w] (wildcard e inside not)"
        (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]
                      [2 :work/posted true 1 1]
                      [2 :work/task "Buy groceries" 1 1]
                      [100 :work/claims 2 1 1]]]
          (is (= #{[1 "Clean room"]}
                 (query/q '[:find ?w ?task :where [?w :work/posted true]
                            [?w :work/task ?task] (not [_ :work/claims ?w])]
                          datoms)))))))
  (testing
    "not-join specifies exactly which variables unify with the outer scope"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; 3 is a document Alice authored
                  [3 :doc/author 1 1 1]
                  ;; 4 is a document Bob authored
                  [4 :doc/author 2 1 1]
                  ;; 3 is published
                  [3 :doc/published true 1 1]]]
      ;; Find users who have NO published documents. The not-join [?u]
      ;; means ?doc is free and local to the not clause.
      (is
        (= #{["Bob"]}
           (query/q
             '[:find ?name :where [?u :user/name ?name]
               (not-join [?u] [?doc :doc/author ?u] [?doc :doc/published true])]
             datoms)))))
  (testing "not-join treats non-joined vars as fresh even when names collide"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; doc 5 is favorited by both; Alice authored doc 6
                  [5 :doc/fav 1 1 1] [5 :doc/fav 2 1 1] [6 :doc/author 1 1 1]]]
      ;; ?d is bound to 5 outside, but is NOT in the join vars, so inside
      ;; the not-join it must be fresh: "?u authored ANY doc", not "?u
      ;; authored doc 5".
      (is (= #{["Bob"]}
             (query/q '[:find ?name :where [?u :user/name ?name]
                        [?d :doc/fav ?u] (not-join [?u] [?d :doc/author ?u])]
                      datoms)))))
  (testing "special-form clauses are recognized as seqs, not only literal lists"
    (let [datoms [[1 :work/posted true 1 1] [2 :work/posted true 1 1]
                  [2 :work/claims "user1" 1 1]]
          not-clause (cons 'not '([?w :work/claims _]))]
      (is (= #{[1]}
             (query/q [:find '?w :where '[?w :work/posted true] not-clause]
                      datoms)))))
  (testing "planner barrier: var bound before use"
    (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]]]
      (is (= #{[1 "Clean room"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        (not [?w :work/claims _]) [?w :work/task ?task]]
                      datoms))))))


;; ---------------------------------------------------------------------------
;; Aggregation (:find aggregates + :with)
;; ---------------------------------------------------------------------------

(deftest aggregation-test
  (testing "grouping: :find ?status (count ?e) groups by ?status"
    (let [datoms [[1 :task/status :open 1 1] [2 :task/status :open 1 1]
                  [3 :task/status :done 1 1]]]
      (is (= #{[:open 2] [:done 1]}
             (query/q '[:find ?status (count ?e) :where
                        [?e :task/status ?status]]
                      datoms)))))
  (testing "sum/min/max/avg over numerics"
    (let [datoms [[1 :item/price 10 1 1] [2 :item/price 20 1 1]
                  [3 :item/price 30 1 1]]]
      (is (= #{[60]}
             (query/q '[:find (sum ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[10]}
             (query/q '[:find (min ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[30]}
             (query/q '[:find (max ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[20.0]}
             (query/q '[:find (avg ?p) :where [?e :item/price ?p]] datoms)))))
  (testing "count-distinct"
    (let [datoms [[1 :item/color :red 1 1] [2 :item/color :red 1 1]
                  [3 :item/color :blue 1 1]]]
      (is (= #{[2]}
             (query/q '[:find (count-distinct ?c) :with ?e :where
                        [?e :item/color ?c]]
                      datoms)))))
  (testing ":with keeps intended duplicates without appearing in the result"
    ;; Two entities share the same price: projected tuples collapse to one
    ;; without :with, so the sum dedupes; :with ?e keeps them distinct.
    (let [datoms [[1 :item/price 10 1 1] [2 :item/price 10 1 1]]]
      (is (= #{[10]}
             (query/q '[:find (sum ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[20]}
             (query/q '[:find (sum ?p) :with ?e :where [?e :item/price ?p]]
                      datoms)))))
  (testing
    "dedupe-before-aggregate: an extra joined var must not inflate the sum"
    ;; Entity 1 has one price but two tags; the ?t join produces two raw
    ;; bindings. Projection to find ∪ :with vars must dedupe them.
    (let [datoms [[1 :item/price 10 1 1] [1 :item/tag :a 1 1]
                  [1 :item/tag :b 1 1]]]
      (is (= #{[10]}
             (query/q '[:find (sum ?p) :with ?e :where [?e :item/price ?p]
                        [?e :item/tag ?t]]
                      datoms)))
      (testing "putting the extra var in :with restores the duplicates"
        (is (= #{[20]}
               (query/q '[:find (sum ?p) :with ?e ?t :where [?e :item/price ?p]
                          [?e :item/tag ?t]]
                        datoms))))))
  (testing "scalar aggregate with no grouping vars yields a single row"
    (let [datoms [[1 :task/status :open 1 1] [2 :task/status :open 1 1]]]
      (is (= #{[2]}
             (query/q '[:find (count ?e) :where [?e :task/status ?s]] datoms)))
      (testing "and an empty result set stays empty"
        (is (= #{}
               (query/q '[:find (count ?e) :where [?e :task/status :missing]]
                        datoms))))))
  (testing "unknown aggregate throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown aggregate"
          (query/q '[:find (median ?p) :where
                     [?e :item/price ?p]]
                   [[1 :item/price 10 1 1]]))))
  (testing "queries without aggregates are unchanged"
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                    sample-datoms)))))


;; ---------------------------------------------------------------------------
;; Predicates & function clauses (caller-supplied :fns registry)
;; ---------------------------------------------------------------------------

(deftest fn-clause-test
  (testing "predicate clause filters bindings"
    (let [datoms [[1 :item/qty 5 1 1] [2 :item/qty 15 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/qty ?n] [(< ?n 10)]]
                      datoms
                      {:fns {'< <}})))))
  (testing "function clause binds its result var"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/qty 3 1 1]]]
      (is (= #{[1 30]}
             (query/q '[:find ?e ?total :where [?e :item/price ?price]
                        [?e :item/qty ?qty] [(* ?price ?qty) ?total]]
                      datoms
                      {:fns {'* *}})))))
  (testing "function clause result unifies (filters) an already-bound var"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/total 30 1 1]
                  [1 :item/qty 3 1 1] [2 :item/price 10 1 1]
                  [2 :item/total 99 1 1] [2 :item/qty 3 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/price ?price]
                        [?e :item/qty ?qty] [?e :item/total ?total]
                        [(* ?price ?qty) ?total]]
                      datoms
                      {:fns {'* *}})))))
  (testing "multi-return vector destructuring"
    (let [datoms [[1 :item/n 7 1 1]]]
      (is (= #{[1 2 1]}
             (query/q '[:find ?e ?q ?r :where [?e :item/n ?n]
                        [(div-mod ?n 3) [?q ?r]]]
                      datoms
                      {:fns {'div-mod (fn [n d] [(quot n d) (rem n d)])}})))))
  (testing "unknown fn throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown query fn"
          (query/q '[:find ?e :where [?e :item/qty ?n]
                     [(< ?n 10)]]
                   [[1 :item/qty 5 1 1]]
                   {:fns {}}))))
  (testing "unbound arg throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unbound variable in fn clause"
          (query/q '[:find ?e :where [(< ?unbound 10)]
                     [?e :item/qty ?n]]
                   [[1 :item/qty 5 1 1]]
                   {:fns {'< <}}))))
  (testing
    "multiple bare result vars throw (Datomic wants a tuple binding [?a ?b])"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"one binding form"
          (query/q '[:find ?e ?q ?r :where [?e :item/n ?n]
                     [(div-mod ?n 3) ?q ?r]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'div-mod (fn [n d]
                                     [(quot n d)
                                      (rem n d)])}}))))
  (testing "tuple binding arity mismatch throws instead of silently truncating"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"tuple binding arity"
          (query/q '[:find ?e ?q ?r ?s :where [?e :item/n ?n]
                     [(div-mod ?n 3) [?q ?r ?s]]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'div-mod (fn [n d]
                                     [(quot n d)
                                      (rem n d)])}}))))
  (testing "unsupported binding forms (collection/relation) throw"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unsupported binding form"
          (query/q '[:find ?e ?y :where [?e :item/n ?n]
                     [(seq-fn ?n) [?y ...]]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'seq-fn (fn [n] [n n])}})))))


;; ---------------------------------------------------------------------------
;; Negation error paths (all vars inside not must be bound; not-join join
;; vars must be bound)
;; ---------------------------------------------------------------------------

(deftest negation-unbound-var-test
  (testing
    "not with a var no prior clause bound throws instead of wildcard-scanning"
    ;; Without the check, FREE ?w acts as a wildcard: any claim anywhere
    ;; makes the negation fail for every candidate — silently wrong #{}.
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"inside not must be bound"
          (query/q '[:find ?w :where (not [?w :work/claims _])]
                   [[1 :work/claims 2 1 1]]))))
  (testing "not with a var found only inside it throws (Datomic: use not-join)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"inside not must be bound"
          (query/q '[:find ?w :where [?w :work/posted true]
                     (not [?c :work/claims ?w])]
                   [[1 :work/posted true 1 1]]))))
  (testing "not-join with an unbound join var throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"not-join variables must be bound"
          (query/q
            '[:find ?name :where
              (not-join [?unbound] [?doc :doc/author ?unbound])]
            [[1 :doc/author 2 1 1]])))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests (RED) — EXPECTED TO FAIL until the bug is fixed.
;; Per TDD red phase: these document real defects found in code review.
;; ---------------------------------------------------------------------------

(deftest nested-not-join-inside-not-bug
  ;; Review finding: eval-not's var-bound check used to flatten EVERY ?-var
  ;; under the `not` via tree-seq, including vars legitimately scoped to a
  ;; nested not-join, throwing spuriously on a legal query. not-join is the
  ;; explicit mechanism for local vars, so the outer `not` must not demand
  ;; them bound. Note the semantics: not-join is itself negation, so
  ;; (not (not-join ...)) is DOUBLE negation — "exists".
  (testing "a not-join nested inside a not scopes its own local var (?r)"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  [10 :review/of 1 1 1]]] ; Alice is reviewed, Bob is not
      (testing "single negation: users with no reviews => Bob"
        (is (= #{["Bob"]}
               (query/q '[:find ?name :where [?u :user/name ?name]
                          (not-join [?u] [?r :review/of ?u])]
                        datoms))))
      (testing "double negation: not(no review of ?u) = reviewed users => Alice"
        (is (= #{["Alice"]}
               (query/q '[:find ?name :where [?u :user/name ?name]
                          (not (not-join [?u] [?r :review/of ?u]))]
                        datoms)))))))


(deftest nested-not-join-local-in-entity-slot-bug
  (testing "same over-reach with the not-join local in the entity slot"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; review 10 is BY Alice (reviewer); none by Bob.
                  [10 :review/reviewer 1 1 1]]]
      ;; not(no review by ?u) = users who reviewed something => Alice.
      (is (= #{["Alice"]}
             (query/q '[:find ?name :where [?u :user/name ?name]
                        (not (not-join [?u] [?r :review/reviewer ?u]))]
                      datoms))))))


;; ---------------------------------------------------------------------------
;; Rules (recursion), Datomic-style: rules bound to % via :in
;; ---------------------------------------------------------------------------

(def family-datoms
  [["adam" :parent "beth" 1 1] ["beth" :parent "cara" 1 1]
   ["cara" :parent "dave" 1 1] ["xeno" :parent "yara" 1 1]])


(def ancestor-rules
  '[[(ancestor ?a ?d) [?a :parent ?d]]
    [(ancestor ?a ?d) [?a :parent ?c] (ancestor ?c ?d)]])


(deftest rules-test
  (testing "a non-recursive rule is a named sub-query"
    (let [datoms [[1 :user/friend 2 1 1] [3 :user/follows 1 1 1]
                  [4 :user/name "loner" 1 1]]
          rules '[[(social ?e) [?e :user/friend _]]
                  [(social ?e) [?e :user/follows _]]]]
      (testing "multiple bodies for one head are a disjunction (OR)"
        (is (=
              #{[1] [3]}
              (query/q '[:find ?e :in $ % :where (social ?e)] datoms rules))))))
  (testing "recursive rule computes the transitive closure"
    (is (= #{["beth"] ["cara"] ["dave"]}
           (query/q '[:find ?d :in $ % :where (ancestor "adam" ?d)]
                    family-datoms
                    ancestor-rules))))
  (testing "recursion with the value side bound works too"
    (is (= #{["adam"] ["beth"] ["cara"]}
           (query/q '[:find ?a :in $ % :where (ancestor ?a "dave")]
                    family-datoms
                    ancestor-rules))))
  (testing "rule invocations join with ordinary clauses"
    ;; every ancestor of the one :active person
    (let [datoms (conj family-datoms ["dave" :status :active 1 1])]
      (is (= #{["adam" "dave"] ["beth" "dave"] ["cara" "dave"]}
             (query/q '[:find ?a ?d :in $ % :where (ancestor ?a ?d)
                        [?d :status :active]]
                      datoms
                      ancestor-rules)))))
  (testing "recursion terminates on cyclic data"
    (let [cycle [["a" :edge "b" 1 1] ["b" :edge "c" 1 1] ["c" :edge "a" 1 1]]
          rules '[[(reach ?x ?y) [?x :edge ?y]]
                  [(reach ?x ?y) [?x :edge ?z] (reach ?z ?y)]]]
      (is (= #{["a"] ["b"] ["c"]}
             (query/q '[:find ?y :in $ % :where (reach "a" ?y)] cycle rules)))))
  (testing "rule body vars are locally scoped, even under colliding names"
    ;; ?c is bound to :blue outside AND is ancestor's internal chain var.
    ;; If the rule saw the outer ?c, its recursive body [?a :parent ?c]
    ;; would unify against :blue and recursion would die after "beth".
    (let [datoms (conj family-datoms ["adam" :color :blue 1 1])]
      (is (= #{[:blue "beth"] [:blue "cara"] [:blue "dave"]}
             (query/q '[:find ?c ?d :in $ % :where ["adam" :color ?c]
                        (ancestor "adam" ?d)]
                      datoms
                      ancestor-rules)))))
  (testing
    "rule head with repeated var yields no solutions for contradictory args"
    (let [datoms [[1 :val 1 1 1] [2 :val 2 1 1]]
          rules '[[(same ?x ?x)]]]
      (is (= #{}
             (query/q '[:find ?e :in $ % :where [?e :val ?v] (same ?v 99)]
                      datoms
                      rules))))))


(deftest rules-error-paths
  (testing "invoking a rule with no % bound throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Nn]o rules"
          (query/q '[:find ?d :where (ancestor "adam" ?d)]
                   family-datoms))))
  (testing "invoking an undefined rule throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown rule"
          (query/q '[:find ?d :in $ % :where
                     (descendant "adam" ?d)]
                   family-datoms
                   ancestor-rules)))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests (RED) — EXPECTED TO FAIL until the bug is fixed.
;; Per TDD red phase: these document real defects found in code review.
;; ---------------------------------------------------------------------------



(def side-effect (atom []))


(defn do-something
  [x]
  (swap! side-effect conj x) [x])


(deftest rule-body-execution-bug
  (testing "contradictory rule head args short-circuit before the body runs"
    (let [datoms [[1 :val 1 1 1]]
          rules '[[(same ?x ?x) [(dao.space.query_test/do-something ?x) [?y]]]]]
      (reset! side-effect [])
      (query/q '[:find ?e :in $ % :where [?e :val ?v] (same ?v 99)]
               datoms
               rules
               {:fns {'dao.space.query_test/do-something do-something}})
      (is (= [] @side-effect)))))
