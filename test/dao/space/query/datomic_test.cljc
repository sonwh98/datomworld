(ns dao.space.query.datomic-test
  "Tests for the Datomic Client query adapter boundary
   (docs/design/dao.space.query.datomic-compat.md, Phase 1).

   Both public arities are driven. What is asserted is the adapter's own
   contract: call-shape normalization, safe string reading, argument and
   option validation before evaluation, pattern padding, pagination after
   result formation, finite-pull admission, and the error taxonomy. None of
   it is Datomic compatibility evidence (that needs the oracle).

   Regression note: the adapter adds a namespace and edits nothing else.
   `dao.space.query-test` (the core suite) and the datomic-q-v1 conformance
   namespace are unchanged and must stay green beside this one.

   This namespace is self-contained: it does not load the corpus."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as core]
            [dao.space.query.datomic :as dq]))


;; ---------------------------------------------------------------------------
;; Fixtures and helpers
;; ---------------------------------------------------------------------------

(def people-facts
  [[1 :person/name "Ada"] [1 :person/age 37] [1 :person/email "a@x"]
   [1 :person/email "a2@x"] [1 :person/friend 2]
   [2 :person/name "Grace"] [2 :person/age 45] [2 :person/friend 3]
   [3 :person/name "Linus"] [3 :person/age 37]
   [4 :person/name "Ken"]])


(defn- people
  []
  (core/fact-relation people-facts))


(defn- people-view
  "An entity view: the explicit current interpreter over d5 rows [e a v t m]."
  []
  (core/current (core/relation (mapv #(conj % 1 1) people-facts))))


(def feed-facts
  (vec (for [e (range 200 210)] [e :item/kind :post])))


(defn- feed
  []
  (core/fact-relation feed-facts))


(defn- error-of
  "The ex-data of the adapter error f throws, ::none when it returns, or
   [::other message] for a non-adapter throwable."
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-data e) [::other (str e)]))))


(def ^:private anomaly-of-kind
  {:invalid-request :cognitect.anomalies/incorrect
   :unsupported :cognitect.anomalies/unsupported})


(defn- refused?
  "True when f throws the adapter error `kind` with the table's anomaly."
  [kind f]
  (let [d (error-of f)]
    (and (map? d)
         (= kind (::dq/error d))
         (= (get anomaly-of-kind kind) (:cognitect.anomalies/category d)))))


;; ---------------------------------------------------------------------------
;; Both arities and query representations
;; ---------------------------------------------------------------------------

(def ^:private ada-query '[:find ?e :where [?e :person/name "Ada"]])


(deftest query-plus-args-arity-takes-every-query-representation
  (let [db (people)]
    (testing "vector"
      (is (= [[1]] (dq/q ada-query db))))
    (testing "list"
      (is (= [[1]] (dq/q '(:find ?e :where [?e :person/name "Ada"]) db))))
    (testing "map"
      (is (= [[1]] (dq/q '{:find [?e] :where [[?e :person/name "Ada"]]} db))))
    (testing "EDN string"
      (is (= [[1]] (dq/q "[:find ?e :where [?e :person/name \"Ada\"]]" db))))
    (testing "no :in means $ is the first argument"
      (is (= [["Grace"]]
             (dq/q '[:find ?n :where [?e :person/age 45] [?e :person/name ?n]] db))))))


(deftest arg-map-arity-takes-every-query-representation
  (let [db (people)]
    (is (= [[1]] (dq/q {:query ada-query :args [db]})))
    (is (= [[1]] (dq/q {:query "[:find ?e :where [?e :person/name \"Ada\"]]"
                        :args [db]})))
    (is (= [[1]] (dq/q {:query '{:find [?e] :where [[?e :person/name "Ada"]]}
                        :args [db]})))
    (testing "the single-argument query arity still validates :in against its arguments"
      (is (refused? :invalid-request #(dq/q ada-query)))
      (is (= [[1]] (dq/q '[:find ?x :in ?x :where [(ground 1) ?y]] 1))
          "no source position at all is legal"))))


(deftest db-first-call-shape-is-refused-loudly
  (let [db (people)]
    (is (refused? :unsupported #(dq/q db {:query ada-query})))
    (is (refused? :unsupported #(dq/q db ada-query)))
    (is (refused? :unsupported #(dq/q db)))))


(deftest the-adapter-result-is-realized-data
  (let [r (dq/q ada-query (people))]
    (is (vector? r))
    (is (every? vector? r))
    (is (not-any? #(and (map? %) (core/value? %)) (tree-seq coll? seq r))
        "no source handle in the output")))


;; ---------------------------------------------------------------------------
;; Safe EDN string reading
;; ---------------------------------------------------------------------------

(deftest string-reading-is-safe-and-bounded
  (let [db (people)]
    (testing "tagged literals and evaluation are not read"
      (is (refused? :unsupported #(dq/q "[:find ?e :where [?e :a #inst \"2020-01-01\"]]" db)))
      (is (refused? :unsupported #(dq/q "#=(+ 1 2)" db)))
      (is (refused? :unsupported #(dq/q "[:find ?e :where [?e :a #uuid \"x\"]]" db)))
      (is (refused? :unsupported #(dq/q "#?(:clj 1) " db))))
    (testing "a # inside a string or a comment is data, not a tag"
      (is (= [] (dq/q "[:find ?e :where [?e :person/name \"#tag\"]]" db)))
      (is (= [[1]] (dq/q "[:find ?e ; #inst here\n :where [?e :person/name \"Ada\"]]" db))))
    (testing "discard and set literals read"
      (is (= [[1]] (dq/q "[:find ?e #_:ignored :where [?e :person/name \"Ada\"]]" db))))
    (testing "unreadable and multi-form text is refused"
      (is (refused? :invalid-request #(dq/q "[:find ?e :where" db)))
      (is (refused? :invalid-request #(dq/q "[:find ?e] [:find ?x]" db)))
      (is (refused? :invalid-request #(dq/q "42" db)))
      (is (refused? :invalid-request #(dq/q "" db))))
    (testing "text is bounded"
      (is (refused? :invalid-request
                    #(dq/q (apply str "[:find ?e :where [?e :person/name \""
                                  (concat (repeat 100001 "x") ["\"]]"]))
                           db)))
      (is (refused? :invalid-request
                    #(dq/q (str (apply str (repeat 70 "[")) (apply str (repeat 70 "]"))) db))))))


;; ---------------------------------------------------------------------------
;; Query shape validation
;; ---------------------------------------------------------------------------

(deftest malformed-queries-are-invalid-requests
  (let [db (people)]
    (is (refused? :invalid-request #(dq/q [] db)))
    (is (refused? :invalid-request #(dq/q '[:where [?e :a _]] db)))
    (is (refused? :invalid-request #(dq/q '[?e :find] db)))
    (is (refused? :invalid-request #(dq/q '[:find ?e :find ?x] db)))
    (is (refused? :invalid-request #(dq/q '{:find [?e] :limit 1} db)))
    (is (refused? :invalid-request #(dq/q 42 db)))
    (is (refused? :invalid-request #(dq/q :not-a-query db)))))


;; ---------------------------------------------------------------------------
;; :args and binding shapes
;; ---------------------------------------------------------------------------

(deftest in-binding-shapes
  (let [db (people)]
    (testing "scalar"
      (is (= [["Grace"]]
             (dq/q '[:find ?n :in $ ?min :where [?e :person/name ?n]
                     [?e :person/age ?a] [(> ?a ?min)]]
                   db 40))))
    (testing "collection"
      (is (= [["Grace"]]
             (dq/q '[:find ?n :in $ [?a ...] :where [?e :person/name ?n] [?e :person/age ?a]]
                   db [45]))))
    (testing "tuple"
      (is (= [[1 "Ada"]] (dq/q '[:find ?e ?n :in $ [?e ?n] :where [?e :person/name ?n]]
                               db [1 "Ada"]))))
    (testing "relation"
      (is (= #{[1] [2]}
             (set (dq/q '[:find ?e :in $ [[?e ?n]] :where [?e :person/name ?n]]
                        db [[1 "Ada"] [2 "Grace"] [3 "nobody"]])))))
    (testing "multiple sources"
      (is (= #{["Ada" 30] ["Grace" 31]}
             (set (dq/q '[:find ?n ?a :in $a $b
                          :where [$a ?e :person/name ?n] [$b ?e :age/v ?a]]
                        db (core/fact-relation [[1 :age/v 30] [2 :age/v 31]]))))))
    (testing "the arg-map :args vector drives the same bindings"
      (is (= [["Grace"]]
             (dq/q {:query '[:find ?n :in $ ?min :where [?e :person/name ?n]
                             [?e :person/age ?a] [(> ?a ?min)]]
                    :args [db 40]}))))))


(deftest args-count-and-shape-are-validated-before-evaluation
  (let [db (people)]
    (is (refused? :invalid-request #(dq/q '[:find ?e :in $ ?x :where [?e :a _]] db)))
    (is (refused? :invalid-request #(dq/q ada-query db db)))
    (is (refused? :invalid-request #(dq/q {:query ada-query :args db})))
    (is (refused? :invalid-request #(dq/q {:query ada-query :args 5})))))


(deftest raw-data-at-source-positions-is-refused
  (testing "raw vectors and maps are not sources"
    (let [e (try (dq/q ada-query [[1 :person/name "Ada"]]) nil
                 (catch #?(:cljd Object :clj Throwable :cljs :default) e e))]
      (is (= :invalid-request (::dq/error (ex-data e))))
      (is (re-find #"relation" (ex-message e)) "the message points at the wrapping constructors"))
    (is (refused? :invalid-request #(dq/q ada-query {:a 1})))
    (is (refused? :invalid-request #(dq/q ada-query nil)))
    (is (refused? :invalid-request
                  #(dq/q {:query ada-query :args [[[1 :person/name "Ada"]]]})))))


(deftest raw-data-in-non-source-positions-stays-data
  (let [db (people)]
    (testing "a vector bound to a scalar variable is not reinterpreted as a db"
      (is (= [[[1 2]]]
             (dq/q '[:find ?v :in $ ?v :where [?e :person/name "Ada"]] db [1 2]))))
    (testing "a map bound to a scalar variable is not reinterpreted"
      (is (= [[{:k 1}]]
             (dq/q '[:find ?v :in $ ?v :where [?e :person/name "Ada"]] db {:k 1}))))
    (testing "a query-value-shaped map at a scalar position is still data"
      (let [shaped (core/relation [[9 :x 9]])]
        (is (= [[shaped]]
               (dq/q '[:find ?v :in $ ?v :where [?e :person/name "Ada"]] db shaped)))))
    (testing "the source position comes from :in, not from argument shape"
      (is (= [[1]] (dq/q '[:find ?e :in ?ignored $ :where [?e :person/name "Ada"]]
                         {:not "a db"} db))))))


;; ---------------------------------------------------------------------------
;; Options: offset, limit, refusals
;; ---------------------------------------------------------------------------

(defn- paged
  [opts]
  (dq/q (merge {:query '[:find ?e :where [?e :item/kind :post]] :args [(feed)]} opts)))


(deftest offset-and-limit-boundaries
  (testing "absent means offset 0 and no limit"
    (is (= 10 (count (paged {})))))
  (testing "limit"
    (is (= 3 (count (paged {:limit 3}))))
    (is (= 0 (count (paged {:limit 0}))))
    (is (= 10 (count (paged {:limit -1}))))
    (is (= 10 (count (paged {:limit 1000000})))))
  (testing "offset"
    (is (= 10 (count (paged {:offset 0}))))
    (is (= 6 (count (paged {:offset 4}))))
    (is (= 0 (count (paged {:offset 10}))))
    (is (= [] (paged {:offset 1000000 :limit 5}))))
  (testing "offset with limit slices the realized rows"
    (is (= 2 (count (paged {:offset 8 :limit 5}))))
    (is (= 3 (count (paged {:offset 2 :limit 3})))))
  (testing "pages tile the full result without overlap"
    (let [all (set (paged {}))
          pages (concat (paged {:offset 0 :limit 4}) (paged {:offset 4 :limit 4})
                        (paged {:offset 8 :limit 4}))]
      (is (= all (set pages)))
      (is (= 10 (count pages))))))


(deftest invalid-offset-and-limit-are-refused
  (is (refused? :invalid-request #(paged {:offset -1})))
  (is (refused? :invalid-request #(paged {:offset 1.5})))
  (is (refused? :invalid-request #(paged {:offset "1"})))
  (is (refused? :invalid-request #(paged {:offset nil})))
  (is (refused? :invalid-request #(paged {:limit -2})))
  (is (refused? :invalid-request #(paged {:limit 2.5})))
  (is (refused? :invalid-request #(paged {:limit "3"})))
  (is (refused? :invalid-request #(paged {:limit nil}))))


(deftest limit-zero-still-evaluates
  (testing "a query that would refuse is refused even with :limit 0"
    (is (refused? :invalid-request
                  #(dq/q {:query '[:find ?e :in $ ?x :where [?e :a _]]
                          :args [(feed)] :limit 0})))))


(deftest unknown-and-unsupported-options-are-refused-by-name
  (doseq [opt [:timeout :ret :io-context :query-stats :stats :bogus]]
    (let [d (error-of #(dq/q {:query ada-query :args [(people)] opt 1}))]
      (is (= :unsupported (::dq/error d)) (str opt))
      (is (= :cognitect.anomalies/unsupported (:cognitect.anomalies/category d)))
      (is (= opt (:option d)) "the refused option is named")))
  (testing ":timeout is refused until the execution-bounds phase"
    (is (refused? :unsupported #(dq/q {:query ada-query :args [(people)] :timeout 1000})))
    (is (refused? :unsupported #(dq/q {:query ada-query :args [(people)] :ret :coll})))))


(deftest pagination-requires-a-collection-find
  (is (refused? :unsupported
                #(dq/q {:query '[:find ?n . :where [?e :person/age 45] [?e :person/name ?n]]
                        :args [(people)] :limit 1})))
  (is (= "Grace"
         (dq/q '[:find ?n . :where [?e :person/age 45] [?e :person/name ?n]] (people)))
      "without pagination the scalar find passes through"))


;; ---------------------------------------------------------------------------
;; Pattern padding
;; ---------------------------------------------------------------------------

(deftest short-patterns-are-padded-to-three-positions
  (doseq [db [(people) (people-view)]]
    (testing "three positions are unchanged"
      (is (= #{[1] [2] [3] [4]}
             (set (dq/q '[:find ?e :where [?e :person/name _]] db)))))
    (testing "two positions pad the value"
      (is (= #{[1] [2] [3] [4]}
             (set (dq/q '[:find ?e :where [?e :person/name]] db))))
      (is (= #{[1] [2] [3]}
             (set (dq/q '[:find ?e :where [?e :person/age]] db)))))
    (testing "one position pads attribute and value"
      (is (= #{[1] [2] [3] [4]} (set (dq/q '[:find ?e :where [?e]] db)))))
    (testing "a selective constant entity stays selective"
      (is (= #{["Ada"]} (set (dq/q '[:find ?n :where [1 :person/name ?n]] db))))
      (is (= #{[:person/name] [:person/age] [:person/email] [:person/friend]}
             (set (dq/q '[:find ?a :where [1 ?a]] db)))))
    (testing "source-qualified short patterns"
      (is (= #{[1] [2] [3]} (set (dq/q '[:find ?e :in $ :where [$ ?e :person/age]] db)))))))


(deftest padding-reaches-nested-clauses-and-rules
  (let [db (people)]
    (testing "not"
      (is (= #{["Ken"]}
             (set (dq/q '[:find ?n :where [?e :person/name ?n] (not [?e :person/age])] db)))))
    (testing "not-join"
      (is (= #{["Grace"] ["Ken"]}
             (set (dq/q '[:find ?n :where [?e :person/name ?n]
                          (not-join [?e] [?e :person/age 37])] db)))))
    (testing "or and or-join"
      (is (= #{["Grace"]}
             (set (dq/q '[:find ?n :where [?e :person/name ?n]
                          (or [?e :person/age 45] [?e :person/friend 99])] db))))
      (is (= #{["Grace"]}
             (set (dq/q '[:find ?n :where [?e :person/name ?n]
                          (or-join [?e] (and [?e :person/age 45] [?e :person/friend]))] db)))))
    (testing "rule bodies passed at %"
      (is (= #{[2] [3]}
             (set (dq/q '[:find ?x :in $ % :where (reach 1 ?x)]
                        db
                        '[[(reach ?a ?b) [?a :person/friend ?b]]
                          [(reach ?a ?b) [?a :person/friend ?m] (reach ?m ?b)]]))))
      (is (= #{[1] [2]}
             (set (dq/q '[:find ?e :in $ % :where (has-friend ?e)]
                        db '[[(has-friend ?e) [?e :person/friend]]])))))))


(deftest history-and-rest-patterns-are-unsupported
  (let [db (people)]
    (is (refused? :unsupported #(dq/q '[:find ?e :where [?e :person/name "Ada" 1 true]] db)))
    (is (refused? :unsupported #(dq/q '[:find ?e :where [?e :person/name "Ada" 1]] db)))
    (is (refused? :unsupported #(dq/q '[:find ?e :where [?e :a ?v 1 2 3]] db)))
    (is (refused? :unsupported #(dq/q '[:find ?e :where [?e & ?r]] db)))
    (is (refused? :invalid-request #(dq/q '[:find ?e :where []] db)))))


;; ---------------------------------------------------------------------------
;; Return maps and aggregates
;; ---------------------------------------------------------------------------

(deftest return-maps-pass-through-and-paginate
  (let [db (people)]
    (is (= [{:name "Grace" :age 45}]
           (dq/q '[:find ?n ?a :keys name age
                   :where [?e :person/name ?n] [?e :person/age ?a] [?e :person/age 45]] db)))
    (is (= [{'name "Grace"}]
           (dq/q '[:find ?n :syms name :where [?e :person/age 45] [?e :person/name ?n]] db)))
    (is (= [{"name" "Grace"}]
           (dq/q '[:find ?n :strs name :where [?e :person/age 45] [?e :person/name ?n]] db)))
    (is (= 2 (count (dq/q {:query '[:find ?n :keys name :where [?e :person/name ?n]]
                           :args [db] :limit 2}))))
    (is (refused? :invalid-request
                  #(dq/q '[:find ?e ?a :keys e :where [?e :person/age ?a]] db)))))


(deftest existing-aggregates-and-groups-work
  (let [db (people)]
    (is (= #{[4]} (set (dq/q '[:find (count ?e) :where [?e :person/name _]] db))))
    (is (= #{[37 2] [45 1]}
           (set (dq/q '[:find ?a (count ?e) :where [?e :person/age ?a]] db))))
    (is (= #{[82 37 45]}
           (set (dq/q '[:find (sum ?a) (min ?a) (max ?a) :where [?e :person/age ?a]] db))))))


(deftest parameterized-and-later-aggregates-are-refused
  (let [db (people)]
    (testing "core would silently drop the parameter, so the adapter refuses the syntax"
      (is (refused? :unsupported #(dq/q '[:find (min 2 ?a) :where [?e :person/age ?a]] db)))
      (is (refused? :unsupported #(dq/q '[:find (max 2 ?a) :where [?e :person/age ?a]] db))))
    (testing "later-phase aggregates"
      (doseq [agg '[distinct median variance stddev rand sample]]
        (is (refused? :unsupported
                      #(dq/q [:find (list agg '?a) :where '[?e :person/age ?a]] db))
            (str agg))))
    (testing "a truly unknown aggregate is an invalid request"
      (is (refused? :invalid-request #(dq/q '[:find (frobnicate ?a) :where [?e :person/age ?a]] db))))))


;; ---------------------------------------------------------------------------
;; Finite pull
;; ---------------------------------------------------------------------------

(defn- pull-of
  [pattern name]
  (ffirst (dq/q [:find (list 'pull '?e pattern) :where ['?e :person/name name]] (people))))


(deftest finite-pull-admitted-profile
  (testing "flat attributes include :db/id"
    (is (= {:person/name "Grace" :person/age 45 :db/id 2}
           (pull-of [:person/name :person/age] "Grace"))))
  (testing "missing attributes are omitted"
    (is (= {:person/name "Ken" :db/id 4} (pull-of [:person/name :person/age] "Ken"))))
  (testing "multivalued attributes produce a collection (order unspecified)"
    (is (= #{"a@x" "a2@x"}
           (set (:person/email (pull-of [:person/name :person/email] "Ada"))))))
  (testing "wildcard"
    (is (= {:person/name "Ken" :db/id 4} (pull-of ['*] "Ken"))))
  (testing "finite nesting"
    (is (= {:person/name "Ada" :db/id 1
            :person/friend {:person/name "Grace" :db/id 2}}
           (pull-of [:person/name {:person/friend [:person/name]}] "Ada"))))
  (testing "validated options: :default"
    (is (= {:person/age 0 :db/id 4} (pull-of [[:person/age :default 0]] "Ken")))
    (is (= {:person/age 37 :db/id 1} (pull-of [[:person/age :default 0]] "Ada"))))
  (testing "validated options: :as"
    (is (= {:n "Ken" :db/id 4} (pull-of [[:person/name :as :n]] "Ken"))))
  (testing "validated options: :limit"
    (is (= 1 (count (:person/email (pull-of [[:person/email :limit 1]] "Ada")))))))


(deftest finite-pull-unvalidated-syntax-is-refused
  (testing "recursion markers"
    (is (refused? :unsupported #(pull-of [:person/name {:person/friend '...}] "Ada")))
    (is (refused? :unsupported #(pull-of [:person/name {:person/friend 2}] "Ada"))))
  (testing "reverse attributes"
    (is (refused? :unsupported #(pull-of [:person/_friend] "Grace")))
    (is (refused? :unsupported #(pull-of [{:person/_friend [:person/name]}] "Grace"))))
  (testing "list-form options are not read"
    (is (refused? :unsupported #(pull-of ['(:person/age :default 0)] "Ken"))))
  (testing "unknown attribute options"
    (is (refused? :unsupported #(pull-of [[:person/age :xform inc]] "Ken"))))
  (testing "bad option values and shapes"
    (is (refused? :invalid-request #(pull-of [[:person/email :limit -1]] "Ada")))
    (is (refused? :invalid-request #(pull-of [[:person/email :limit]] "Ada")))
    (is (refused? :invalid-request #(pull-of [42] "Ada")))
    (is (refused? :invalid-request #(pull-of {:a 1} "Ada"))))
  (testing "dynamic and source-qualified patterns"
    (is (refused? :unsupported
                  #(dq/q '[:find (pull ?e ?pattern) :in $ ?pattern :where [?e :person/name "Ada"]]
                         (people) [:person/name])))
    (is (refused? :unsupported
                  #(dq/q '[:find (pull $ ?e [:person/name]) :where [?e :person/name "Ada"]]
                         (people)))))
  (testing "multi-entry nested maps would drop entries silently"
    (is (refused? :unsupported
                  #(pull-of [{:person/friend [:person/name] :person/email [:x]}] "Ada")))))


;; ---------------------------------------------------------------------------
;; Error taxonomy
;; ---------------------------------------------------------------------------

(def ^:private allowed-context-keys
  #{::dq/error :cognitect.anomalies/category :option :clause :variable})


(deftest every-error-carries-the-discriminator-anomaly-and-minimal-context
  (let [db (people)
        thrown [#(dq/q db ada-query)
                #(dq/q ada-query [[1 2 3]])
                #(dq/q {:query ada-query :args [db] :timeout 5})
                #(dq/q {:query ada-query :args [db] :offset -1})
                #(dq/q '[:find ?e :where [?e :a ?v 1 2]] db)
                #(dq/q '[:find (min 2 ?a) :where [?e :person/age ?a]] db)
                #(dq/q "#=(foo)" db)
                #(dq/q '[:find ?e :in $ ?x :where [?e :a _]] db)
                #(dq/q '[:find ?e :where [?e :person/age ?v] [(unknown-fn ?v) ?x]] db)]]
    (doseq [f thrown]
      (let [d (error-of f)]
        (is (map? d))
        (is (contains? #{:invalid-request :unsupported :evaluation-failed :source-failed}
                       (::dq/error d)))
        (is (= (get {:invalid-request :cognitect.anomalies/incorrect
                     :unsupported :cognitect.anomalies/unsupported}
                    (::dq/error d))
               (:cognitect.anomalies/category d)))
        (is (every? allowed-context-keys (keys d))
            (str "minimal ex-data only: " (pr-str (keys d))))
        (is (not-any? #(core/value? %) (vals d)) "no source handle")))))


(deftest core-refusals-do-not-leak-bindings
  (let [d (error-of #(dq/q '[:find ?n :where [?e :person/name ?n]
                             (not-join [?e] [(no-such-fn ?e) ?z])]
                           (people)))]
    (is (= :invalid-request (::dq/error d)))
    (is (not-any? #{:dao.space.query/dbs :bindings :binding} (keys d)))
    (is (every? allowed-context-keys (keys d)))))


(deftest source-failure-preserves-the-cause-and-its-category
  (let [boom (ex-info "disk gone" {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                                   :secret "not copied"})
        db {:dao.space.query/relation (delay (throw boom))}
        e (try (dq/q '[:find ?e :where [?e :a _]] db)
               nil
               (catch #?(:cljd Object :clj Throwable :cljs :default) e e))]
    (is (some? e))
    (is (= :source-failed (::dq/error (ex-data e))))
    (is (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category (ex-data e))))
    (is (identical? boom (ex-cause e)) "the underlying cause is preserved")
    (is (not (contains? (ex-data e) :secret)) "third-party data is not copied")))


#?(:clj
   (deftest unexpected-evaluator-failure-keeps-its-cause
     (let [e (try (dq/q '[:find ?y :in $ ?a :where [?e :person/name "Ada"] [(+ ?a ?a) ?y]]
                        (people) "text")
                  nil
                  (catch Throwable e e))]
       (is (some? e))
       (is (contains? #{:evaluation-failed :invalid-request} (::dq/error (ex-data e))))
       (when (= :evaluation-failed (::dq/error (ex-data e)))
         (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category (ex-data e))))
         (is (some? (ex-cause e)))))))


;; ---------------------------------------------------------------------------
;; Statelessness and the core contract
;; ---------------------------------------------------------------------------

(deftest the-adapter-borrows-its-sources
  (let [db (people)
        before (pr-str db)
        r1 (dq/q ada-query db)
        r2 (dq/q ada-query db)]
    (is (= r1 r2) "repeated calls agree: no hidden state")
    (is (= before (pr-str db)) "the source value is unchanged")))


(deftest core-q-and-collect-are-unchanged-beside-the-adapter
  (testing "core keeps exact arity: the two-position pattern is the adapter's job"
    (is (= #{} (core/collect (core/q '[:find ?e :where [?e :person/name]] (people)))))
    (is (= #{[1] [2] [3] [4]}
           (core/collect (core/q '[:find ?e :where [?e :person/name _]] (people))))))
  (testing "core still returns a set where the adapter returns a vector"
    (is (set? (core/collect (core/q ada-query (people)))))
    (is (vector? (dq/q ada-query (people))))))
