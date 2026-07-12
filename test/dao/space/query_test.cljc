(ns dao.space.query-test
  "Contract tests for dao.space.query: the pure, stateless match/q/pull
  library specified in docs/design/dao.space.md and docs/datomic-pull.md.
  Exercises the four source shapes the design doc's Source Polymorphism
  section requires — a single dao.jing handle, a collection of dao.jing
  handles (federated query, justified by ADR 0001's monoid homomorphism),
  a raw vector of datoms, and a raw vector of entity maps — plus mixes of
  them, and pull's schema-free entity-projection contract."
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
             (query/entity-attrs datoms 1)))))
  (testing
    "entity with no datoms returns {} — unlike pull, entity-attrs
            never includes :db/id, matching Datomic's entity/touch
            convention rather than pull's convention"
    (is (= {} (query/entity-attrs [[1 :name "Alice" 1 1]] 999)))))


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
  (testing "unknown fn throws when not in the registry or builtins"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown query fn"
          (query/q '[:find ?e :where [?e :item/qty ?n]
                     [(unknown-fn ?n 10)]]
                   [[1 :item/qty 5 1 1]]))))
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


;; ---------------------------------------------------------------------------
;; or / or-join / and (Phase 1)
;; ---------------------------------------------------------------------------

(deftest or-test
  (testing "or unifies bindings from any branch (set semantics across branches)"
    (let [datoms [[1 :type :dog 1 1] [2 :type :cat 1 1] [3 :type :fish 1 1]]]
      (is (= #{[1] [2]}
             (query/q '[:find ?e :where (or [?e :type :dog] [?e :type :cat])]
                      datoms)))))
  (testing "an or branch with extra free vars must be detected (same-var rule)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Ss]ame.*variable|branches"
          (query/q '[:find ?e :where (or [?e :a ?x] [?e :b ?y])]
                   [[1 :a 1 1 1] [1 :b 2 1 1]]))))
  (testing "set semantics: identical bindings from two branches appear once"
    (let [datoms [[1 :kind :cat 1 1] [1 :pet true 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where (or [?e :kind :cat] [?e :pet true])]
                      datoms)))))
  (testing "or with no branch satisfied yields no bindings"
    (is (= #{}
           (query/q '[:find ?e :where (or [?e :a 1] [?e :a 2])]
                    [[1 :a 3 1 1]])))))


(deftest or-join-test
  (testing "or-join declares which vars unify with the outer scope"
    (let [datoms [[1 :x 10 1 1] [1 :flag true 1 1] [2 :x 20 1 1]
                  [2 :flag false 1 1]]]
      ;; ?x is declared in the join vars so it is allowed to flow out
      ;; of either branch (the engine strips branch-only vars; ?x is
      ;; not branch-only here).
      (is
        (=
          #{[1 10] [2 20] [1 1]}
          (query/q '[:find ?e ?x :where
                     (or-join
                       [?e ?x]
                       [?e :x ?x]
                       (and [?e :flag true] [(identity ?e) ?x]))]
                   datoms
                   {:fns {'identity identity}})))))
  (testing "branch-only vars do not escape the join declaration"
    ;; ?x is bound inside the branch but not declared in the join;
    ;; it must not appear in the outer result.
    (let [datoms [[1 :x 10 1 1] [2 :x 20 1 1]]]
      (is (= #{[1] [2]}
             (query/q '[:find ?e :where (or-join [?e] [?e :x ?x])] datoms)))))
  (testing "or-join can introduce its own join var when unbound"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :where (or-join [?e] [?e :a 1] [?e :a 2])]
                    [[1 :a 1 1 1] [2 :a 2 1 1] [3 :a 3 1 1]]))))
  (testing "or-join preserves outer-scope vars (merge back into outer binding)"
    ;; ?n is bound by the top-level pattern; the or-join then adds
    ;; a join var (or introduces a new one). The merged binding must
    ;; carry ?n through — the engine should augment, not replace.
    (is (= #{["a" 1]}
           (query/q '[:find ?n ?e :where [?e :name ?n]
                      (or-join [?e] [?e :flag true])]
                    [[1 :name "a" 1 1] [1 :flag true 1 1]]))))
  (testing "branches do not see non-join outer vars (isolation)"
    ;; ?v is bound to 99 in the outer scope but NOT declared as a join var.
    ;; Inside the branch, ?v should be a fresh local var that ranges
    ;; freely, NOT filtered by the outer binding's ?v = 99. If isolation is
    ;; broken, the branch would filter on ?v = 99 (no match). With correct
    ;; isolation, ?v matches the :tag value (1) and succeeds.
    (let [datoms [[1 :name "a" 1 1] [1 :val 99 1 1] [1 :tag 1 1 1]]]
      (is (= #{["a" 1]}
             (query/q '[:find ?n ?e :where [?e :name ?n] [?e :val ?v]
                        (or-join [?e] [?e :tag ?v])]
                      datoms))))))


(deftest and-test
  (testing "and groups clauses inside an or branch"
    (let [datoms [[1 :x 5 1 1] [1 :y 5 1 1] [2 :x 5 1 1] [2 :y 4 1 1]
                  [3 :flag true 1 1]]]
      (is
        (=
          #{[1] [3]}
          (query/q '[:find ?e :where
                     (or-join
                       [?e]
                       (and [?e :x ?x] [?e :y ?y] [(= ?x ?y)])
                       [?e :flag true])]
                   datoms
                   {:fns {'= =}}))))))


;; ---------------------------------------------------------------------------
;; Find specs and return maps (Phase 2)
;; ---------------------------------------------------------------------------

(deftest find-scalar-test
  (testing "[:find ?x .] returns a single value, or nil for no results"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= "Alice" (query/q '[:find ?n . :where [1 :name ?n]] datoms)))
      (is (nil? (query/q '[:find ?n . :where [99 :name ?n]] datoms)))))
  (testing "scalar find spec composes with aggregates"
    (is (= 3
           (query/q '[:find (count ?e) . :where [?e :task/status :open]]
                    [[1 :task/status :open 1 1] [2 :task/status :open 1 1]
                     [3 :task/status :open 1 1]])))))


(deftest find-coll-test
  (testing "single var coll returns flat vector of values"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]
                  [3 :name "Charlie" 1 1]]]
      (is (= #{"Alice" "Bob" "Charlie"}
             (set (query/q '[:find [?n ...] :where [_ :name ?n]] datoms))))))
  (testing "collection find on empty results is an empty vector"
    (is (= [] (query/q '[:find [?n ...] :where [_ :name ?n]] []))))
  (testing "multi-var coll returns vector of tuples"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (query/q '[:find [?e ?n ...] :where [?e :name ?n]]
                           datoms)))))))


(deftest find-tuple-test
  (testing "[:find [?x ?y]] returns a single tuple (or nil)"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1]]]
      (is (= ["Alice" 30]
             (query/q '[:find [?n ?a] :where [1 :name ?n] [1 :age ?a]]
                      datoms))))
    (testing "no results yields nil"
      (is (nil? (query/q '[:find [?n ?a] :where [99 :name ?n]] []))))))


(deftest return-maps-keys-test
  (testing ":keys returns a seq of maps with the named keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]]
      (is (= #{{:e 1, :n "Alice", :a 30} {:e 2, :n "Bob", :a 40}}
             (set (query/q '[:find ?e ?n ?a :keys e n a :where [?e :name ?n]
                             [?e :age ?a]]
                           datoms))))))
  (testing ":keys arity must match the find vars (throws otherwise)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Rr]eturn map arity|arity must match"
          (query/q '[:find ?e ?n :keys e :where [?e :name ?n]]
                   [[1 :name "Alice" 1 1]]))))
  (testing ":keys is relation-only (no scalar/coll/tuple find specs)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Rr]eturn map form.*relation"
          (query/q '[:find ?e . :keys e :where [?e :name ?n]]
                   [[1 :name "Alice" 1 1]])))))


(deftest return-maps-syms-strs-test
  (testing ":syms returns a seq of maps with symbol keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]
          result (query/q '[:find ?e ?n :syms e n :where [?e :name ?n]
                            [?e :age ?a]]
                          datoms)]
      (is (every? map? result))
      (is (= #{[1 "Alice"] [2 "Bob"]} (set (map (juxt 'e 'n) result))))))
  (testing ":syms keys are symbols (e n), not keywords (:e :n)"
    (let [datoms [[1 :name "Alice" 1 1]]
          result (query/q '[:find ?e :syms e :where [?e :name _]] datoms)]
      (is (every? #(contains? % 'e) result))
      (is (not-any? #(contains? % :e) result))))
  (testing ":strs returns a seq of maps with string keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]
          result (query/q '[:find ?e ?n :strs e n :where [?e :name ?n]
                            [?e :age ?a]]
                          datoms)]
      (is (every? map? result))
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (map (juxt #(get % "e") #(get % "n")) result)))))))


;; ---------------------------------------------------------------------------
;; VAET read path (Phase 4): v-only patterns answer from the VAET index
;; ---------------------------------------------------------------------------

(deftest vaet-vbound-test
  (testing "v-only match over a raw datom vector exercises in-memory VAET"
    (let [datoms [[1 :item/category :fruit 1 1] [2 :item/category :fruit 1 1]
                  [3 :item/category :vegetable 1 1]]]
      (is (= #{[1 :item/category :fruit 1 1] [2 :item/category :fruit 1 1]}
             (set (query/match datoms ['_ '_ :fruit]))))))
  (testing "v-only q (who points at X?) over a raw datom vector"
    (let [datoms [[1 :doc/author 2 1 1] [3 :doc/author 2 1 1]
                  [4 :doc/author 5 1 1]]]
      (is (= #{[1] [3]}
             (query/q '[:find ?e :where [?e :doc/author 2]] datoms)))))
  (testing "v-only with as-of bounds the index slice"
    (let [datoms [[1 :doc/author 2 1 1] [1 :doc/author 2 5 1]
                  [3 :doc/author 2 3 1]]]
      ;; as-of 1 excludes the t=3 and t=5 datoms
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :doc/author 2]] datoms {:as-of 1})))
      ;; as-of 4 includes t=1 and t=3
      (is (= #{[1] [3]}
             (query/q '[:find ?e :where [?e :doc/author 2]]
                      datoms
                      {:as-of 4}))))))


;; ---------------------------------------------------------------------------

(deftest builtin-fns-test
  (testing "comparators are in the default registry (no :fns option needed)"
    (let [datoms [[1 :item/qty 5 1 1] [2 :item/qty 15 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/qty ?n] [(< ?n 10)]]
                      datoms)))))
  (testing "arithmetic fns are in the default registry"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/qty 3 1 1]]]
      (is (= #{[1 30]}
             (query/q '[:find ?e ?total :where [?e :item/price ?p]
                        [?e :item/qty ?q] [(* ?p ?q) ?total]]
                      datoms)))))
  (testing "str / count / first / last / get / nth are builtins"
    (is (= #{["Alice"]}
           (query/q '[:find ?u :where [1 :name ?n] [(str ?n) ?u]]
                    [[1 :name "Alice" 1 1]])))
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{["Alice!"] ["Bob!"]}
             (query/q '[:find ?c :where [?e :name ?n] [(str ?n "!") ?c]]
                      datoms))))
    (is (= #{[5]}
           (query/q '[:find ?l :where [_ :name "Alice"] [(count "Alice") ?l]]
                    [[1 :name "Alice" 1 1]]))))
  (testing "ground binds a literal to a variable"
    (is (= #{[42]} (query/q '[:find ?x :where [(ground 42) ?x]] [])))))


(deftest get-else-test
  (testing "returns the attr value or the default when the entity lacks it"
    (is (= #{["Alice"]}
           (query/q '[:find ?v :where [(get-else $ 1 :name "anon") ?v]]
                    [[1 :name "Alice" 1 1]])))
    (is (= #{["anon"]}
           (query/q '[:find ?v :where [(get-else $ 2 :name "anon") ?v]]
                    [[1 :name "Alice" 1 1]])))))


(deftest missing?-test
  (testing "predicate is true when the entity lacks the attribute"
    (is (= #{[1]}
           (query/q '[:find ?e :where [?e :name "Alice"] [(missing? $ 1 :age)]]
                    [[1 :name "Alice" 1 1]])))
    (is (= #{}
           (query/q '[:find ?e :where [?e :name "Alice"] [(missing? $ 1 :name)]]
                    [[1 :name "Alice" 1 1]])))))


(deftest builtins-disable-test
  (testing "the explicit :builtins false opt removes the default registry"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown query fn"
          (query/q '[:find ?e :where [?e :item/qty ?n]
                     [(< ?n 10)]]
                   [[1 :item/qty 5 1 1]]
                   {:builtins false})))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests
;; ---------------------------------------------------------------------------

(deftest get-else-with-query-var-test
  (testing "get-else resolves query variables in entity position"
    (let [datoms [[1 :name "Alice" 1 1] [2 :age 30 1 1]]]
      ;; ?e is bound by the pattern clause, then used in get-else
      (is (= #{[1 "Alice"]}
             (query/q '[:find ?e ?v :where [?e :name _]
                        [(get-else $ ?e :name "anon") ?v]]
                      datoms)))
      ;; entity 2 lacks :name, should return default
      (is (= #{[2 "anon"]}
             (query/q '[:find ?e ?v :where [?e :age _]
                        [(get-else $ ?e :name "anon") ?v]]
                      datoms))))))


(deftest missing?-with-query-var-test
  (testing "missing? resolves query variables in entity position"
    (let [datoms [[1 :name "Alice" 1 1] [2 :age 30 1 1]]]
      ;; entity 2 is missing :name -> predicate is true -> binding survives
      (is (= #{[2]}
             (query/q '[:find ?e :where [?e :age _] [(missing? $ ?e :name)]]
                      datoms)))
      ;; entity 1 has :name -> predicate is false -> binding filtered out
      (is (= #{}
             (query/q '[:find ?e :where [?e :name _] [(missing? $ ?e :name)]]
                      datoms))))))


(deftest or-with-not-local-vars-test
  (testing "or same-var rule ignores vars local to not/not-join"
    (let [datoms [[1 :a 1 1 1] [1 :b 1 1 1] [2 :a 2 1 1] [2 :c 2 1 1]]]
      ;; Branch 1 has ?x in a positive clause (output). Branch 2 has ?x in
      ;; a positive clause AND ?y inside a not-join
      ;; (a free, existentially-scoped var that must NOT count toward
      ;; the branch's output set for the same-var rule).
      ;; The same-var rule should see both branches as {?e, ?x} and
      ;; pass. Branch 1 yields {?e 1, ?x 1} and {?e 2, ?x 2}.
      ;; Branch 2 yields {?e 1, ?x 1} only (entity 2 lacks :b).
      ;; Union: {[1 1] [2 2]}.
      (is (= #{[1 1] [2 2]}
             (query/q
               '[:find ?e ?x :where
                 (or [?e :a ?x] (and [?e :b ?x] (not-join [?e ?x] [?e :c ?y])))]
               datoms))))))


(deftest rules-inside-not-or-join-test
  (testing "rules can be called inside not-join and or-join"
    (let [datoms [[1 :type :dog 1 1] [2 :type :cat 1 1] [3 :type :bird 1 1]]
          rules '[[(is-cat ?e) [?e :type :cat]]]]
      ;; test not-join with rule
      (is (= #{[1] [3]}
             (query/q '[:find ?e :in $ % :where [?e :type _]
                        (not-join [?e] (is-cat ?e))]
                      datoms
                      rules)))
      ;; test or-join with rule
      (is (= #{[2]}
             (query/q '[:find ?e :in $ % :where (or-join [?e] (is-cat ?e))]
                      datoms
                      rules))))))


(deftest or-join-branch-must-bind-join-vars-test
  (testing
    "a branch that fails to bind a declared join var throws
            instead of leaking the engine's FREE sentinel into results"
    ;; (or-join [?e ?x] [?e :a 1]) — the single branch binds ?e but
    ;; never ?x. DataScript rejects this via the same-var rule; without
    ;; validation the engine binds ?x to the internal ::free sentinel
    ;; and it escapes into the :find output.
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"join var"
          (query/q '[:find ?e ?x :where
                     (or-join [?e ?x] [?e :a 1])]
                   [[1 :a 1 1 1]]))))
  (testing
    "the check is per branch: one conforming branch does not
            excuse another that misses a join var"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"join var"
          (query/q '[:find ?e ?x :where
                     (or-join [?e ?x] [?e :a ?x] [?e :b 1])]
                   [[1 :a 1 1 1] [1 :b 1 1 1]])))))


;; ---------------------------------------------------------------------------
;; Pull: declarative entity projection (formerly dao.space.pull, merged
;; 2026-07-13 — see docs/datomic-pull.md and the Pull section banner in
;; query.cljc for why)
;; ---------------------------------------------------------------------------

;; Increment 1: Parser

(deftest parse-pattern-attrs-test
  (testing "simple attr list"
    (is (= {:attrs [:name :age], :wildcard? false, :nested {}}
           (query/parse-pattern [:name :age]))))
  (testing "empty pattern"
    (is (= {:attrs [], :wildcard? false, :nested {}}
           (query/parse-pattern [])))))


(deftest parse-pattern-wildcard-test
  (testing "wildcard ' [*] "
    (is (= {:attrs [], :wildcard? true, :nested {}}
           (query/parse-pattern '[*])))))


(deftest parse-pattern-nested-test
  (testing "nested map spec"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (query/parse-pattern [{:friend [:name]}])))))


(deftest parse-pattern-reverse-test
  (testing "reverse ref :_attr"
    (is (= {:attrs [:_friend], :wildcard? false, :nested {}}
           (query/parse-pattern [:_friend]))))
  (testing "reverse ref with nested"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:_friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (query/parse-pattern [{:_friend [:name]}])))))


(deftest parse-pattern-options-test
  (testing "attr with :default"
    (is (= {:attrs [{:attr :age, :default 0}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:age :default 0]]))))
  (testing "attr with :limit"
    (is (= {:attrs [{:attr :tags, :limit 5}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:tags :limit 5]]))))
  (testing "attr with :as"
    (is (= {:attrs [{:attr :name, :as :label}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:name :as :label]])))))


(deftest parse-pattern-malformed-test
  (testing "non-keyword/symbol/map/vector throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (query/parse-pattern [123]))))
  (testing "vector with non-keyword first element throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (query/parse-pattern [[123 :default 0]])))))


;; Increment 2: Flat pull

(def pull-sample-datoms
  [[1 :name "Alice" 1 1] [1 :age 30 1 1] [1 :tag "dev" 1 1] [1 :tag "admin" 2 1]
   [2 :name "Bob" 1 1]])


(deftest pull-flat-attrs-test
  (testing "simple attr list returns scalar for single-valued, vector for multi"
    (let [result (query/pull pull-sample-datoms 1 [:name :age :tag])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result))))))
  (testing "missing attr is omitted"
    (is (= {:db/id 2, :name "Bob"}
           (query/pull pull-sample-datoms 2 [:name :age])))))


(deftest pull-flat-wildcard-test
  (testing "wildcard includes all attrs"
    (let [result (query/pull pull-sample-datoms 1 '[*])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result)))))))


(deftest pull-flat-options-test
  (testing ":default for missing attr"
    (is (= {:db/id 2, :name "Bob", :age 0}
           (query/pull pull-sample-datoms 2 [:name [:age :default 0]]))))
  (testing ":limit bounds multi-valued results"
    (let [result (query/pull pull-sample-datoms 1 [[:tag :limit 1]])]
      (is (= 1 (:db/id result)))
      (is (= 1 (count (:tag result))))
      (is (contains? #{"dev" "admin"} (first (:tag result))))))
  (testing ":limit on single-valued attribute does not force vector wrapping"
    (is (= {:db/id 1, :name "Alice"}
           (query/pull pull-sample-datoms 1 [[:name :limit 5]]))))
  (testing ":as renames output key"
    (is (= {:db/id 1, :label "Alice"}
           (query/pull pull-sample-datoms 1 [[:name :as :label]])))))


(deftest pull-flat-db-id-only-for-absent-test
  (testing
    "entity with no datoms returns {:db/id eid}, matching Datomic —
            pull never returns nil at the top level, since entity ids
            are not existence-checked; it always echoes back :db/id"
    (is (= {:db/id 999} (query/pull pull-sample-datoms 999 [:name])))))


;; Increment 3: Nested maps (forward navigation)

(def pull-nested-datoms
  [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 2 1] [2 :name "Bob" 1 1]
   [2 :friend 3 1 1] [3 :name "Charlie" 1 1]])


(deftest pull-many-test
  (testing "pull-many folds once, maps over eids"
    (is (= [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"} {:db/id 999}]
           (query/pull-many pull-sample-datoms [1 2 999] [:name]))))
  (testing "pull-many supports nested and reverse specs"
    (let [res
          (query/pull-many pull-nested-datoms [1] [:name {:friend [:name]}])]
      (is (= [{:db/id 1,
               :name "Alice",
               :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}]
             (mapv (fn [item] (update item :friend #(sort-by :db/id %))) res))))
    (let [res
          (query/pull-many pull-nested-datoms [3] [:name {:_friend [:name]}])]
      (is (= [{:db/id 3,
               :name "Charlie",
               :_friend [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}]}]
             (mapv (fn [item] (update item :_friend #(sort-by :db/id %)))
                   res))))))


(deftest pull-nested-test
  (testing "nested map spec navigates forward refs"
    (is (= {:db/id 1,
            :name "Alice",
            :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}
           (let [result
                 (query/pull pull-nested-datoms 1 [:name {:friend [:name]}])]
             (update result :friend #(sort-by :db/id %))))))
  (testing "value addressing no datoms is omitted"
    (is (= {:db/id 3, :name "Charlie"}
           (query/pull pull-nested-datoms 3 [:name {:friend [:name]}]))))
  (testing "nesting depth > 2"
    (let [result (query/pull pull-nested-datoms
                             1
                             [:name {:friend [:name {:friend [:name]}]}])
          friends (sort-by :db/id (:friend result))]
      (is (= 2 (count friends)))
      (is (= "Bob" (:name (first friends))))
      ;; Bob has one friend (Charlie), so scalar per entity-attrs
      ;; convention
      (is (= {:db/id 3, :name "Charlie"} (:friend (first friends)))))))


;; Increment 4: Reverse refs

(deftest pull-reverse-test
  (testing "reverse ref :_attr returns vector of entities pointing here"
    (let [result (query/pull pull-nested-datoms 2 [:name :_friend])]
      (is (= "Bob" (:name result)))
      ;; Entity 1 has friend 2, so :_friend should include entity 1
      (is (= [{:db/id 1}] (mapv #(select-keys % [:db/id]) (:_friend result))))))
  (testing "reverse ref with nested"
    (let [result (query/pull pull-nested-datoms 3 [:name {:_friend [:name]}])
          friends (sort-by :db/id (:_friend result))]
      ;; Entities 1 and 2 both have friend 3
      (is (= 2 (count friends)))
      (is (= "Alice" (:name (first friends))))
      (is (= "Bob" (:name (second friends))))))
  (testing "reverse ref supports :default option"
    (is (= {:db/id 1, :name "Alice", :_friend []}
           (query/pull pull-nested-datoms 1 [:name [:_friend :default []]])))))


;; A reverse-ref pull against a raw datom vector only exercises the
;; in-memory index that `fold` builds fresh each call. This test
;; publishes first (JVM-only: persistent-sorted-set durability) so the
;; reverse probe reaches `restored-indexes`' lazily-restored AVET psset
;; instead — the only way to prove the persisted-index path answers
;; `:_attr` correctly, not just the eager in-memory one.
#?(:cljd nil
   :clj (deftest pull-reverse-reaches-the-published-index
          (let [store (jing/create-kv-mem)]
            (jing/cas! store
                       index/default-datoms-key
                       0
                       {:datoms pull-nested-datoms})
            (index/publish-index! store)
            (let [result (query/pull store 3 [:name {:_friend [:name]}])
                  friends (sort-by :db/id (:_friend result))]
              (is (= "Charlie" (:name result)))
              (is (= 2 (count friends)))
              (is (= "Alice" (:name (first friends))))
              (is (= "Bob" (:name (second friends)))))
            (let [flat (query/pull store 2 [:name :_friend])]
              (is (= "Bob" (:name flat)))
              (is (= [{:db/id 1}]
                     (mapv #(select-keys % [:db/id]) (:_friend flat))))))))


;; ---------------------------------------------------------------------------
;; Increment 6: (pull ?e pattern) as a q find element
;; ---------------------------------------------------------------------------
;;
;; Pull and q now live in the same namespace (merged 2026-07-13), so a
;; pull find element resolves the `$` source's already-folded index
;; directly via `resolve-db` — no DI, no `:pull-fn` option, no per-row
;; re-fold. Pull find elements still bind to `$` only in this pass.


(deftest pull-find-element-basic-test
  (testing "(pull ?e pattern) as a find element projects each row through pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :age 30}] [{:db/id 2, :name "Bob"}]}
             (set (query/q '[:find (pull ?e [:name :age]) :where [?e :name _]]
                           datoms))))))
  (testing "pull find element composes with a plain var in the same relation"
    (let [datoms [[1 :name "Alice" 1 1]]]
      (is (= #{[1 {:db/id 1, :name "Alice"}]}
             (set (query/q '[:find ?e (pull ?e [:name]) :where [?e :name _]]
                           datoms)))))))


(deftest pull-find-element-nested-and-reverse-test
  (testing
    "pull find element pattern supports nested/reverse specs, same as direct pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :friend {:db/id 2, :name "Bob"}}]}
             (set (query/q '[:find (pull ?e [:name {:friend [:name]}]) :where
                             [?e :name "Alice"]]
                           datoms)))))))


(deftest pull-find-element-scalar-spec-test
  (testing "pull composes with the scalar find spec"
    (is (= {:db/id 1, :name "Alice"}
           (query/q '[:find (pull ?e [:name]) . :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-coll-spec-test
  (testing "pull composes with the collection find spec"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}}
             (set (query/q '[:find [(pull ?e [:name]) ...] :where [?e :name _]]
                           datoms)))))))


(deftest pull-find-element-tuple-spec-test
  (testing "pull composes with the tuple find spec, alongside a plain var"
    (is (= [1 {:db/id 1, :name "Alice"}]
           (query/q '[:find [?e (pull ?e [:name])] :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-rejects-aggregate-test
  (testing "an aggregate cannot wrap a pull find element"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Pp]ull"
          (query/q '[:find (count (pull ?e [:name])) :where
                     [?e :name _]]
                   [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-no-dollar-source-test
  (testing
    "pull find element throws when $ was never bound (an :in shape
            that binds ?e directly, bypassing any $-pattern clause)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"\$ source"
          (query/q '[:find (pull ?e [:name]) :in ?e] 1)))))


(deftest pull-find-element-with-aggregate-test
  (testing "pull find element composes with aggregates (acts as grouping var)"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 1 1]
                  [2 :name "Bob" 1 1] [2 :friend 3 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice"} 2] [{:db/id 2, :name "Bob"} 1]}
             (set (query/q '[:find (pull ?e [:name]) (count ?friend) :where
                             [?e :name _] [?e :friend ?friend]]
                           datoms)))))))
