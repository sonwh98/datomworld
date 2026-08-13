(ns dao.space.positional-query-test
  "Contract tests for q as a generic positional Datalog evaluator.

   Bare vector collections are raw relations. Plain clauses match exact
   arity; `&` is the explicit prefix/rest operator. Datom current-state and
   history semantics are selected with explicit source views."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]))


(defn- view
  "Resolve a proposed public view constructor dynamically so the JVM red
   phase reports individual failures before the production vars exist."
  [view-name source]
  #?(:clj (if-let [f (resolve (symbol "dao.space.query" (name view-name)))]
            (f source)
            (throw (ex-info
                     (str "query/" (name view-name) " is not implemented")
                     {:view view-name})))
     :default ((case view-name
                 :current query/current
                 :history query/history)
               source)))


(deftest mixed-arity-tuples-join-by-value-test
  (testing "plain clauses match exact arity and shared vars join relations"
    (is (= #{[1 "Ada" 37]}
           (query/q '[:find ?e ?name ?age :where [?e :person/first-name ?name]
                      [?e ?age]]
                    [[1 :person/first-name "Ada"] [1 37]
                     [2 :person/first-name "Grace"]]))))
  (testing "a shorter clause does not prefix-match a longer tuple"
    (is (= #{}
           (query/q '[:find ?e ?v :where [?e ?v]]
                    [[1 :person/first-name "Ada"]]))))
  (testing "a longer clause does not match a shorter tuple"
    (is (= #{} (query/q '[:find ?e ?a ?v :where [?e ?a ?v]] [[1 37]])))))


(deftest arbitrary-tuple-arities-test
  (let [tuples [[42] [1 :edge/to 2] [7 :sensor/x 1.0 2.0]
                [9 :frame/x 1 2 3 :ordinary-sixth-value]]]
    (is (= #{[42]} (query/q '[:find ?v :where [?v]] tuples)))
    (is (= #{[1 2]}
           (query/q '[:find ?from ?to :where [?from :edge/to ?to]] tuples)))
    (is (= #{[7 1.0 2.0]}
           (query/q '[:find ?id ?x ?y :where [?id :sensor/x ?x ?y]] tuples)))
    (testing "a six-tuple is generic and its final value is not a namespace"
      (is (= #{[9 :ordinary-sixth-value]}
             (query/q '[:find ?id ?last :where [?id :frame/x _ _ _ ?last]]
                      tuples))))))


(deftest explicit-rest-pattern-test
  (let [tuples [[1 :person/name "Ada" :source/a 10] [2 :person/name "Grace"]
                [3 :person/name]]]
    (testing "& _ explicitly ignores an empty or non-empty tail"
      (is (= #{[1 "Ada"] [2 "Grace"]}
             (query/q '[:find ?e ?name :where [?e :person/name ?name & _]]
                      tuples))))
    (testing "& ?tail binds the remaining slots as a vector"
      (is (= #{[1 [:source/a 10]] [2 []]}
             (query/q '[:find ?e ?tail :where [?e :person/name ?name & ?tail]]
                      tuples))))))


(deftest malformed-rest-patterns-are-rejected-test
  (let [tuples [[1 :person/name "Ada"]]]
    (doseq [bad-query ['[:find ?e :where [?e :person/name &]]
                       '[:find ?e :where [?e & ?tail ?extra]]
                       '[:find ?e :where [?e & ?tail & ?other]]]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"rest|&|pattern"
            (query/q bad-query tuples))))))


(deftest exact-arity-is-preserved-inside-logical-forms-test
  (testing "negation ranges only over rows of the negated clause's arity"
    (is (= #{[2]}
           (query/q '[:find ?e :where [?e :tag _] (not [?e :blocked])]
                    [[1 :tag "x"] [1 :blocked] [2 :tag "y"]
                     [2 :blocked :by-admin]]))))
  (testing "or branches retain their individual exact arities"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :where (or [?e :a] [?e :b :c])]
                    [[1 :a] [2 :b :c] [3 :b :c :extra]]))))
  (testing "rule body clauses use the same exact-arity contract"
    (is (= #{[1]}
           (query/q '[:find ?e :in $ % :where (admin ?e)]
                    [[1 :admin] [2 :admin :temporary]]
                    '[[(admin ?e) [?e :admin]]])))))


(deftest explicit-sources-join-across-arities-test
  (is (= #{[1 "Ada" 37] [2 "Grace" 41]}
         (query/q '[:find ?e ?name ?age :in $people $ages :where
                    [$people ?e :person/name ?name] [$ages ?e ?age]]
                  [[1 :person/name "Ada"] [2 :person/name "Grace"]]
                  [[1 37] [2 41]]))))


(deftest source-scope-is-query-context-not-a-tuple-slot-test
  (let [people-log [[1 :person/name "Ada" 1 1]]
        ages-log [[1 :person/age 37 1 1]]]
    (testing
      "physical sources stay separate unless clauses explicitly join them"
      (is (= #{[1 "Ada" 37]}
             (query/q '[:find ?e ?name ?age :in $people $ages :where
                        [$people ?e :person/name ?name]
                        [$ages ?e :person/age ?age]]
                      (view :current people-log)
                      (view :current ages-log)))))
    (testing "a container of physical sources is not an implicit merged db"
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"separate database inputs|one physical"
            (query/q '[:find ?e :where [?e _ _]]
                     (view :current [people-log ages-log])))))))


(deftest datom-interpretation-is-an-explicit-view-test
  (let [log [[1 :person/color "red" 1 1] [1 :person/color "red" 2 0]
             [1 :person/color "blue" 3 1]]]
    (testing "a bare d5 collection is raw and matches only d5 clauses"
      (is (= #{["red" 1 1] ["red" 2 0] ["blue" 3 1]}
             (query/q '[:find ?color ?t ?m :where
                        [1 :person/color ?color ?t ?m]]
                      log)))
      (is (= #{}
             (query/q '[:find ?color :where [1 :person/color ?color]] log))))
    (testing "current explicitly resolves validity and projects d5 to d3"
      (is (= #{["blue"]}
             (query/q '[:find ?color :where [1 :person/color ?color]]
                      (view :current log)))))
    (testing "history explicitly exposes exact stored d5 tuples"
      (is (= #{["red" 1 1] ["red" 2 0] ["blue" 3 1]}
             (query/q '[:find ?color ?t ?m :where
                        [1 :person/color ?color ?t ?m]]
                      (view :history log)))))))


(deftest current-view-composes-with-as-of-test
  (let [log [[1 :person/color "red" 1 1] [1 :person/color "red" 3 0]
             [1 :person/color "blue" 4 1]]]
    (is (= #{["red"]}
           (query/q '[:find ?color :where [1 :person/color ?color]]
                    (view :current log)
                    {:as-of 2})))
    (is (= #{["blue"]}
           (query/q '[:find ?color :where [1 :person/color ?color]]
                    (view :current log))))))


(deftest entity-map-view-remains-explicit-in-its-shape-test
  (is (= #{[1 "Ada"]}
         (query/q '[:find ?e ?name :where [?e :person/name ?name]]
                  {:db/id 1, :person/name "Ada"}))))


(deftest fact-only-operations-reject-uninterpreted-relations-test
  (let [raw [[1 :person/name "Ada" 1 1]]
        history (view :history raw)]
    (doseq [db-value [raw history]]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"current fact-shaped"
            (query/pull db-value 1 [:person/name])))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"current fact-shaped"
            (query/q '[:find ?name :where
                       [(get-else $ 1 :person/name "none")
                        ?name]]
                     db-value)))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"current fact-shaped"
            (query/q '[:find ?missing :where
                       [(missing? $ 1 :person/name) ?missing]]
                     db-value))))))


(deftest datom-only-options-require-explicit-views-test
  (let [raw [[1 :person/name "Ada" 1 1]]]
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"current or history"
          (query/q '[:find ?row :where [?row & _]] raw {:as-of 1})))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"canonical d5"
          (query/fold [[1 :person/name "Ada"]])))))


(deftest input-arity-is-exact-test
  (is (thrown-with-msg? #?(:cljs js/Error
                           :cljd Object
                           :default Exception)
                        #"input arity"
        (query/q '[:find ?e :in $ ?wanted :where [?e ?wanted]]
                 [[1 :x]])))
  (is (thrown-with-msg?
        #?(:cljs js/Error
           :cljd Object
           :default Exception)
        #"input arity|options"
        (query/q '[:find ?e :where [?e]] [[1]] :not-an-options-map)))
  (is (thrown-with-msg?
        #?(:cljs js/Error
           :cljd Object
           :default Exception)
        #"input arity"
        (query/q '[:find ?e :where [?e]] [[1]] {:as-of 1} {:fns {}}))))


(deftest rest-tail-participates-in-unification-test
  (is (= #{[1 [:x 9]]}
         (query/q '[:find ?e ?tail :where [?e :left & ?tail]
                    [?e :right & ?tail]]
                  [[1 :left :x 9] [1 :right :x 9] [2 :left :x 9]
                   [2 :right :different]]))))


(deftest rest-pattern-composes-with-logical-forms-test
  (testing "or branches use the same explicit-rest interpretation"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :where (or [?e :left & _] [?e :right :x & _])]
                    [[1 :left :anything 9] [2 :right :x :tail]
                     [3 :right :not-x]]))))
  (testing "not uses rest only when the query author requests it"
    (is (= #{[2]}
           (query/q
             '[:find ?e :where [?e :candidate & _] (not [?e :blocked & _])]
             [[1 :candidate] [1 :blocked :reason] [2 :candidate :metadata]])))))
