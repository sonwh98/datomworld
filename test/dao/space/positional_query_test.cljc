(ns dao.space.positional-query-test
  "Contract tests for q as a generic positional Datalog evaluator over bounded
   DaoStreams. Inline relation descriptors carry arbitrary, mixed-dimensional
   tuples; arity is shape, never semantics. Datom current-state and history
   semantics are selected with the explicit `current`/`history` interpreters."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]))


(defn- qq
  "Collect a q result: (qq form & inputs)."
  [form & inputs]
  (query/collect (apply query/q form inputs)))


(defn- rel
  [tuples]
  (query/relation tuples))


(defn- cur
  [d5]
  (query/current (query/relation d5)))


(deftest mixed-arity-tuples-join-by-value-test
  (testing "plain clauses match exact arity and shared vars join relations"
    (is (= #{[1 "Ada" 37]}
           (qq '[:find ?e ?name ?age :where [?e :person/first-name ?name]
                 [?e ?age]]
               (rel [[1 :person/first-name "Ada"] [1 37]
                     [2 :person/first-name "Grace"]])))))
  (testing "a shorter clause does not prefix-match a longer tuple"
    (is (= #{}
           (qq '[:find ?e ?v :where [?e ?v]]
               (rel [[1 :person/first-name "Ada"]])))))
  (testing "a longer clause does not match a shorter tuple"
    (is (= #{} (qq '[:find ?e ?a ?v :where [?e ?a ?v]] (rel [[1 37]]))))))


(deftest arbitrary-tuple-arities-test
  (let [tuples [[42] [1 :edge/to 2] [7 :sensor/x 1.0 2.0]
                [9 :frame/x 1 2 3 :ordinary-sixth-value]
                [:a :b :c :d :e :f :g :ordinary-eighth-value]]]
    (is (= #{[42]} (qq '[:find ?v :where [?v]] (rel tuples))))
    (is (= #{[1 2]}
           (qq '[:find ?from ?to :where [?from :edge/to ?to]] (rel tuples))))
    (is (= #{[7 1.0 2.0]}
           (qq '[:find ?id ?x ?y :where [?id :sensor/x ?x ?y]] (rel tuples))))
    (testing "a six-tuple is generic and its final value is not a namespace"
      (is (= #{[9 :ordinary-sixth-value]}
             (qq '[:find ?id ?last :where [?id :frame/x _ _ _ ?last]]
                 (rel tuples)))))
    (testing "query evaluation has no maximum tuple dimension"
      (is (= #{[:ordinary-eighth-value]}
             (qq '[:find ?last :where [_ _ _ _ _ _ _ ?last]] (rel tuples)))))))


(deftest explicit-rest-pattern-test
  (let [tuples [[1 :person/name "Ada" :source/a 10] [2 :person/name "Grace"]
                [3 :person/name]]]
    (testing "& _ explicitly ignores an empty or non-empty tail"
      (is (= #{[1 "Ada"] [2 "Grace"]}
             (qq '[:find ?e ?name :where [?e :person/name ?name & _]]
                 (rel tuples)))))
    (testing "& ?tail binds the remaining slots as a vector"
      (is (= #{[1 [:source/a 10]] [2 []]}
             (qq '[:find ?e ?tail :where [?e :person/name ?name & ?tail]]
                 (rel tuples)))))))


(deftest malformed-rest-patterns-are-rejected-test
  (let [tuples [[1 :person/name "Ada"]]]
    (doseq [bad-query ['[:find ?e :where [?e :person/name &]]
                       '[:find ?e :where [?e & ?tail ?extra]]
                       '[:find ?e :where [?e & ?tail & ?other]]]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"rest|&|pattern"
            (qq bad-query (rel tuples)))))))


(deftest exact-arity-is-preserved-inside-logical-forms-test
  (testing "negation ranges only over rows of the negated clause's arity"
    (is (= #{[2]}
           (qq '[:find ?e :where [?e :tag _] (not [?e :blocked])]
               (rel [[1 :tag "x"] [1 :blocked] [2 :tag "y"]
                     [2 :blocked :by-admin]])))))
  (testing "or branches retain their individual exact arities"
    (is (= #{[1] [2]}
           (qq '[:find ?e :where (or [?e :a] [?e :b :c])]
               (rel [[1 :a] [2 :b :c] [3 :b :c :extra]])))))
  (testing "rule body clauses use the same exact-arity contract"
    (is (= #{[1]}
           (qq '[:find ?e :in $ % :where (admin ?e)]
               (rel [[1 :admin] [2 :admin :temporary]])
               '[[(admin ?e) [?e :admin]]])))))


(deftest explicit-sources-join-across-arities-test
  (is (= #{[1 "Ada" 37] [2 "Grace" 41]}
         (qq '[:find ?e ?name ?age :in $people $ages :where
               [$people ?e :person/name ?name] [$ages ?e ?age]]
             (rel [[1 :person/name "Ada"] [2 :person/name "Grace"]])
             (rel [[1 37] [2 41]])))))


(deftest datom-interpretation-is-an-explicit-view-test
  (let [log [[1 :person/color "red" 1 1] [1 :person/color "red" 2 0]
             [1 :person/color "blue" 3 1]]]
    (testing "a bare relation is raw and matches only exact-shape clauses"
      (is (= #{["red" 1 1] ["red" 2 0] ["blue" 3 1]}
             (qq '[:find ?color ?t ?m :where [1 :person/color ?color ?t ?m]]
                 (rel log))))
      (is (= #{}
             (qq '[:find ?color :where [1 :person/color ?color]] (rel log)))))
    (testing "current explicitly resolves validity and projects d5 to d3"
      (is (= #{["blue"]}
             (qq '[:find ?color :where [1 :person/color ?color]] (cur log)))))
    (testing "history explicitly exposes exact stored d5 tuples"
      (is (= #{["red" 1 1] ["red" 2 0] ["blue" 3 1]}
             (qq '[:find ?color ?t ?m :where [1 :person/color ?color ?t ?m]]
                 (query/history (rel log))))))))


(deftest current-view-composes-with-as-of-test
  (let [log [[1 :person/color "red" 1 1] [1 :person/color "red" 3 0]
             [1 :person/color "blue" 4 1]]]
    (is (= #{["red"]}
           (qq '[:find ?color :where [1 :person/color ?color]]
               (query/current (rel log) 2))))
    (is (= #{["blue"]}
           (qq '[:find ?color :where [1 :person/color ?color]]
               (query/current (rel log)))))))


(deftest source-scope-is-query-context-not-a-tuple-slot-test
  (let [people-log [[1 :person/name "Ada" 1 1]]
        ages-log [[1 :person/age 37 1 1]]]
    (testing
      "physical sources stay separate unless clauses explicitly join them"
      (is (= #{[1 "Ada" 37]}
             (qq '[:find ?e ?name ?age :in $people $ages :where
                   [$people ?e :person/name ?name] [$ages ?e :person/age ?age]]
                 (cur people-log)
                 (cur ages-log)))))))


(deftest input-arity-is-exact-test
  (is (thrown-with-msg? #?(:cljs js/Error
                           :cljd Object
                           :default Exception)
                        #"input arity"
        (qq '[:find ?e :in $ ?wanted :where [?e ?wanted]]
            (rel [[1 :x]]))))
  (is (thrown-with-msg?
        #?(:cljs js/Error
           :cljd Object
           :default Exception)
        #"input arity|options"
        (qq '[:find ?e :where [?e]] (rel [[1]]) :not-an-options-map))))


(deftest rest-tail-participates-in-unification-test
  (is (= #{[1 [:x 9]]}
         (qq '[:find ?e ?tail :where [?e :left & ?tail] [?e :right & ?tail]]
             (rel [[1 :left :x 9] [1 :right :x 9] [2 :left :x 9]
                   [2 :right :different]])))))
