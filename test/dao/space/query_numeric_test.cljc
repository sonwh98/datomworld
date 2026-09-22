(ns dao.space.query-numeric-test
  "dao.space.query over Jing numeric kinds and carriers
   (docs/design/dao.jing.cbor.md, Numeric identity and the Required test
   scenarios bullet on decoded numeric carriers).

   `=`, `not=` and unification are kind-strict (dao.jing.cbor/content=):
   kind, decimal scale and zero sign all matter, integer width does not.
   `< > <= >=` order by exact value across every kind and carrier. `min`
   and `max` (builtin and aggregate) return one operand by the owner's
   three-part tie-break. Arithmetic stays host-native and refuses carriers
   loudly."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [dao.space.index :as index]
            [dao.space.query :as query])
  #?@(:cljd [(:import ["dart:core" BigInt])]))


;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- big
  [s]
  #?(:cljd (BigInt.parse s) :clj (bigint s) :cljs (js/BigInt s)))


(def pos-zero (cbor/float64-from-bits "0000000000000000"))
(def neg-zero (cbor/float64-from-bits "8000000000000000"))
(def pos-inf (cbor/float64-from-bits "7ff0000000000000"))
(def neg-inf (cbor/float64-from-bits "fff0000000000000"))
(def nan (cbor/float64-from-bits "7ff8000000000000"))
(def other-nan (cbor/float64-from-bits "7ff8000000000001"))


(defn- qq
  [form & inputs]
  (query/collect (apply query/q form inputs)))


(defn- d5
  [e a v]
  [e a v 1 datom/default-op])


(defn- current
  "A current view over raw d5 rows: queries over it route through the
   covered indexes (the range scans this namespace pins)."
  [rows]
  (query/current (query/relation rows)))


(defn- holds?
  "Does the builtin op hold for scalar operands a and b inside a query."
  [op a b]
  (boolean (seq (qq [:find '?a :in '$ '?a '?b :where [(list op '?a '?b)]]
                    (query/relation []) a b))))


(defn- call
  "The value a two-operand builtin returns inside a query."
  [op a b]
  (qq [:find '?r '. :in '$ '?a '?b :where [(list op '?a '?b) '?r]]
      (query/relation []) a b))


(defn- refusal-message
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         #?(:cljd (str e) :default (ex-message e)))))


(defn- hex
  [x]
  (fx/bytes->hex (cbor/encode x)))


(defn- same-content?
  "The result is the expected operand: content= and, decisively, the same
   canonical bytes (so kind survives the query and re-encodes)."
  [expected actual]
  (and (cbor/content= expected actual) (= (hex expected) (hex actual))))


;; ---------------------------------------------------------------------------
;; = and not= are kind-strict
;; ---------------------------------------------------------------------------

(deftest equality-is-kind-strict
  (testing "different kinds are never ="
    (is (not (holds? '= 1 (cbor/float64 1))))
    (is (not (holds? '= (cbor/float64 1) 1)))
    (is (not (holds? '= 1 (cbor/ratio 1 1))))
    (is (not (holds? '= (cbor/decimal 0 1) 1)))
    (is (holds? 'not= 1 (cbor/float64 1))))
  (testing "scale and zero sign are significant within a kind"
    (is (not (holds? '= (cbor/decimal -1 10) (cbor/decimal -2 100))))
    (is (not (holds? '= pos-zero neg-zero)))
    (is (holds? 'not= pos-zero neg-zero)))
  (testing "integer width is not"
    (is (holds? '= 1 (big "1")))
    (is (holds? '= (big "1") 1))
    (is (not (holds? 'not= 1 (big "1")))))
  (testing "exact large values"
    (is (not (holds? '= (big "9007199254740992") (cbor/float64 9007199254740992))))
    (is (holds? '= (big "18446744073709551616") (big "18446744073709551616"))))
  (testing "NaN is one content, so = holds; infinities are themselves"
    (is (holds? '= nan other-nan))
    (is (holds? '= pos-inf pos-inf))
    (is (not (holds? '= pos-inf neg-inf)))))


(deftest constant-patterns-match-kind-strictly-through-the-index
  ;; AVET scans [a v] by index order, where 1 and 1.0 tie and interleave by
  ;; e: e 2 (1.0) sorts before e 3 (1) and e 4 (1.0). A scan that stopped at
  ;; the first kind-distinct row would miss e 3.
  (let [db (current [(d5 2 :n/v (cbor/float64 1)) (d5 3 :n/v 1)
                     (d5 4 :n/v (cbor/float64 1)) (d5 5 :n/v (big "1"))
                     (d5 6 :n/v (cbor/ratio 1 1)) (d5 7 :n/v 2)])]
    (is (= #{3 5} (set (qq '[:find [?e ...] :where [?e :n/v 1]] db))))
    (is (= #{2 4} (set (qq [:find ['?e '...] :where ['?e :n/v (cbor/float64 1)]] db))))
    (is (= #{6} (set (qq [:find ['?e '...] :where ['?e :n/v (cbor/ratio 1 1)]] db))))
    (testing "the v-only route (VAET) too"
      (is (= #{3 5} (set (qq '[:find [?e ...] :where [?e _ 1]] db)))))
    (testing "datoms selects kind-strictly"
      (let [idx (index/index-datoms [(d5 2 :n/v (cbor/float64 1)) (d5 3 :n/v 1)])]
        (is (= [3] (map index/datom-e (query/datoms idx '_ :n/v 1))))))))


(deftest unification-is-kind-strict
  (let [db (current [(d5 1 :n/v 1) (d5 2 :m/v (cbor/float64 1))
                     (d5 3 :m/v 1) (d5 4 :m/v (big "1"))
                     (d5 5 :n/v (cbor/decimal -1 10)) (d5 6 :m/v (cbor/decimal -2 100))
                     (d5 7 :m/v (cbor/decimal -1 10))
                     (d5 8 :n/v pos-zero) (d5 9 :m/v neg-zero)])]
    (is (= #{[1 3] [1 4] [5 7]}
           (set (qq '[:find ?a ?b :where [?a :n/v ?v] [?b :m/v ?v]] db))))
    (testing "a collection value containing a carrier unifies only with the same kinds"
      (let [db (current [(d5 1 :c/v [1 (cbor/decimal -1 10)])
                         (d5 2 :c/v [1 (cbor/decimal -2 100)])
                         (d5 3 :c/v [(big "1") (cbor/decimal -1 10)])
                         (d5 4 :c/v [(cbor/float64 1) (cbor/decimal -1 10)])])]
        (is (= #{3}
               (set (qq '[:find [?b ...] :where [1 :c/v ?v] [?b :c/v ?v] [(not= ?b 1)]]
                        db))))))))


(deftest facts-keep-kind-strict-identity-in-the-current-view
  ;; Host = merges 1.0M with 1.00M and 0.0 with -0.0 on the JVM; the current
  ;; view must neither supersede nor retract one fact by the other.
  (let [retract (:db/retract datom/reserved)
        rows [[1 :n/v (cbor/decimal -1 10) 1 datom/default-op]
              [1 :n/v (cbor/decimal -2 100) 2 datom/default-op]
              [1 :n/v pos-zero 1 datom/default-op]
              [1 :n/v neg-zero 2 retract]]
        live (query/current-state-seq rows)]
    (is (= 3 (count live)) "1.0M, 1.00M and 0.0 are three live facts")
    (is (some #(same-content? (cbor/decimal -1 10) (nth % 2)) live))
    (is (some #(same-content? (cbor/decimal -2 100) (nth % 2)) live))
    (is (some #(same-content? pos-zero (nth % 2)) live)
        "retracting -0.0 leaves the 0.0 fact")))


;; ---------------------------------------------------------------------------
;; Ordering builtins
;; ---------------------------------------------------------------------------

(def ladder
  [neg-inf (big "-100000000000000000000") (cbor/decimal -1 -15) (cbor/ratio -1 2)
   0 (cbor/ratio 1 10) (cbor/float64 0.1) (cbor/decimal -1 2) 1 (cbor/float64 1.5)
   (big "9007199254740992") (big "9007199254740993") pos-inf nan])


(deftest ordering-is-exact-across-kinds-in-both-operand-orders
  (doseq [[i a] (map-indexed vector ladder)
          [j b] (map-indexed vector ladder)]
    (is (= (< i j) (holds? '< a b)) (pr-str ['< a b]))
    (is (= (> i j) (holds? '> a b)) (pr-str ['> a b]))
    (is (= (<= i j) (holds? '<= a b)) (pr-str ['<= a b]))
    (is (= (>= i j) (holds? '>= a b)) (pr-str ['>= a b])))
  (testing "ties across kinds, scale and zero sign order as equal"
    (doseq [[a b] [[1 (cbor/float64 1)] [1 (cbor/ratio 1 1)] [neg-zero pos-zero]
                   [(cbor/decimal -1 10) (cbor/decimal -2 100)] [nan other-nan]]]
      (is (holds? '<= a b))
      (is (holds? '>= b a))
      (is (not (holds? '< a b)))
      (is (not (holds? '< b a))))))


(deftest ordering-filters-datoms-by-value
  (let [db (current [(d5 1 :n/v (cbor/ratio 1 3)) (d5 2 :n/v (cbor/decimal -1 5))
                     (d5 3 :n/v (cbor/float64 0.75)) (d5 4 :n/v 1)
                     (d5 5 :n/v (big "2"))])]
    (is (= #{1 2}
           (set (qq '[:find [?e ...] :in $ ?lim :where [?e :n/v ?v] [(< ?v ?lim)]]
                    db (cbor/ratio 3 4))))
        "0.75 equals 3/4 exactly, so it is not below it")
    (is (= #{1 2 3}
           (set (qq '[:find [?e ...] :in $ ?lim :where [?e :n/v ?v] [(<= ?v ?lim)]]
                    db (cbor/float64 0.75)))))))


(deftest ordering-refuses-non-numbers-explicitly
  (doseq [op ['< '> '<= '>= 'min 'max]]
    (is (re-find #"requires numeric operands"
                 (or (refusal-message #(holds? op "a" 1)) ""))
        (str op))))


;; ---------------------------------------------------------------------------
;; min / max: the three-part tie-break, shared by builtin and aggregate
;; ---------------------------------------------------------------------------

(def tie-cases
  "[a b expected]: a and b are numerically equal, expected is the operand
   min and max both return in either argument order."
  [[1 (cbor/float64 1) 1]
   [(cbor/decimal -1 10) (cbor/float64 1) (cbor/decimal -1 10)]
   [(cbor/ratio 1 1) (cbor/float64 1) (cbor/ratio 1 1)]
   [(cbor/decimal -1 10) (cbor/decimal -2 100) (cbor/decimal -1 10)]
   [1 (cbor/ratio 1 1) 1]
   [(cbor/decimal 0 1) (cbor/ratio 1 1) (cbor/decimal 0 1)]
   [pos-zero neg-zero pos-zero]
   [0 neg-zero 0]])


(deftest min-max-tie-break-is-order-and-host-independent
  (doseq [[a b expected] tie-cases
          op ['min 'max]]
    (is (same-content? expected (call op a b)) (pr-str [op a b]))
    (is (same-content? expected (call op b a)) (pr-str [op b a])))
  (testing "encoded lengths for the rule-2 cases, as the ruling describes them"
    (is (< (count (hex (cbor/decimal -1 10))) (count (hex (cbor/decimal -2 100)))))
    (is (< (count (hex 1)) (count (hex (cbor/ratio 1 1))))))
  (testing "non-ties return the lesser or greater by value"
    (is (same-content? (cbor/ratio 1 3) (call 'min (cbor/float64 0.5) (cbor/ratio 1 3))))
    (is (same-content? (cbor/float64 0.5) (call 'max (cbor/float64 0.5) (cbor/ratio 1 3))))
    (is (same-content? (big "9007199254740993")
                       (call 'max (cbor/float64 9007199254740992) (big "9007199254740993"))))))


(deftest min-max-aggregates-agree-with-the-builtin
  (let [values [(cbor/float64 1) 1 (cbor/ratio 1 1) (cbor/decimal -2 100)
                (cbor/decimal -1 10) (big "1")]
        rows (map-indexed (fn [i v] [i :n/v v]) values)]
    (doseq [order [rows (reverse rows)]]
      (let [db (query/relation order)
            [lo hi] (qq '[:find [(min ?v) (max ?v)] :where [_ :n/v ?v]] db)]
        (is (same-content? 1 lo) (pr-str lo))
        (is (same-content? 1 hi) (pr-str hi))
        (is (same-content? (reduce #(call 'min %1 %2) values) lo))))))


;; ---------------------------------------------------------------------------
;; Arithmetic stays host-native and refuses carriers loudly
;; ---------------------------------------------------------------------------

(def carriers
  "Values dao.jing.cbor treats as numbers that this host does not."
  #?(:cljd [(cbor/decimal -1 10) (cbor/ratio 1 2) (big "1")]
     :clj [(cbor/ratio 1 1)]
     :cljs [(cbor/float64 1.5) (cbor/decimal -1 10) (cbor/ratio 1 2) (big "1")]))


(deftest arithmetic-refuses-carriers-loudly
  (doseq [carrier carriers
          op ['+ '- '* '/ 'quot 'rem 'mod]]
    (is (re-find #"does not accept a numeric carrier"
                 (or (refusal-message #(call op carrier 1)) ""))
        (pr-str [op carrier])))
  (doseq [carrier carriers
          op ['inc 'dec 'abs]]
    (is (re-find #"does not accept a numeric carrier"
                 (or (refusal-message
                       #(qq [:find '?r '. :in '$ '?a :where [(list op '?a) '?r]]
                            (query/relation []) carrier))
                     ""))
        (pr-str [op carrier])))
  (testing "sum and avg aggregates refuse them too"
    (doseq [carrier carriers
            agg ['sum 'avg]]
      (is (re-find #"does not accept a numeric carrier"
                   (or (refusal-message
                         #(qq [:find (list agg '?v) '. :where ['_ :n/v '?v]]
                              (query/relation [[1 :n/v carrier] [2 :n/v 1]])))
                       ""))
          (pr-str [agg carrier]))))
  (testing "host numbers still compute"
    (is (= 3 (call '+ 1 2)))
    (is (= 6 (qq '[:find (sum ?v) . :where [_ :n/v ?v]]
                 (query/relation [[1 :n/v 1] [2 :n/v 2] [3 :n/v 3]]))))))


;; ---------------------------------------------------------------------------
;; [e a 1 t m] versus [e a 1.0 t m]
;; ---------------------------------------------------------------------------

(deftest numerically-equal-facts-share-an-index-entry-but-not-an-identity
  (let [int-row (d5 1 :n/v 1)
        float-row (d5 1 :n/v (cbor/float64 1))]
    (testing "one covered-index entry"
      (let [idx (index/index-datoms [int-row float-row])]
        (is (= 1 (count (seq (:eavt idx)))))
        (is (= 1 (count (seq (:avet idx)))))))
    (testing "two distinct content addresses"
      (is (not= (hex int-row) (hex float-row))))
    (testing "and they do not unify against each other in a query"
      (let [db (current [(d5 1 :n/v 1) (d5 2 :m/v (cbor/float64 1))])]
        (is (empty? (qq '[:find ?a ?b :where [?a :n/v ?v] [?b :m/v ?v]] db)))
        (is (empty? (qq '[:find ?a :where [?a :n/v ?v] [(= ?v 1.5)]] db)))
        (is (= #{[1]} (set (qq '[:find ?a :where [?a :n/v ?v] [(<= ?v 1)]] db))))))))


(deftest query-results-preserve-kind-on-re-encoding
  (doseq [v [(cbor/float64 1) (cbor/decimal -2 100) (cbor/ratio 1 1) neg-zero
             (big "18446744073709551616") nan]]
    (let [out (qq '[:find ?v . :where [_ :n/v ?v]] (current [(d5 1 :n/v v)]))]
      (is (same-content? v out) (pr-str v)))))


;; ---------------------------------------------------------------------------
;; The query's own output: deduplication is kind-strict end to end
;; ---------------------------------------------------------------------------

(def scale-and-sign-rows
  "Four facts whose values differ only in decimal scale or float zero sign:
   host = merges each pair on the JVM (and 0.0/-0.0 on Dart)."
  [[1 :n/v (cbor/decimal -1 10)] [2 :n/v (cbor/decimal -2 100)]
   [3 :n/v pos-zero] [4 :n/v neg-zero]])


(deftest count-distinct-counts-content-distinct-values
  (is (= 4 (qq '[:find (count-distinct ?v) . :where [_ :n/v ?v]]
               (query/relation scale-and-sign-rows)))))


(defn- group-count
  "The count a [?v (count ?e)] result gives the group whose ?v is content=
   to v, or nil."
  [result v]
  (some (fn [[gv n]] (when (same-content? v gv) n)) result))


(deftest aggregate-groups-split-on-scale-and-zero-sign
  (let [result (qq '[:find ?v (count ?e) :where [?e :n/v ?v]]
                   (query/relation [[1 :n/v (cbor/decimal -1 10)]
                                    [2 :n/v (cbor/decimal -1 10)]
                                    [3 :n/v (cbor/decimal -2 100)]
                                    [4 :n/v pos-zero]
                                    [5 :n/v pos-zero]
                                    [6 :n/v neg-zero]]))]
    (is (= 4 (count result)) (pr-str result))
    (is (= 2 (group-count result (cbor/decimal -1 10))))
    (is (= 1 (group-count result (cbor/decimal -2 100))))
    (is (= 2 (group-count result pos-zero)))
    (is (= 1 (group-count result neg-zero)))))


(deftest or-and-or-join-keep-bindings-that-differ-in-scale-or-sign
  (let [db (query/relation [[1 :a/v (cbor/decimal -1 10)] [1 :b/v (cbor/decimal -2 100)]
                            [2 :a/v pos-zero] [2 :b/v neg-zero]])]
    (is (= 4 (qq '[:find (count-distinct ?v) . :where (or [?e :a/v ?v] [?e :b/v ?v])]
                 db)))
    (is (= 4 (qq '[:find (count-distinct ?v) .
                   :where [?e _ _] (or-join [?e ?v] [?e :a/v ?v] [?e :b/v ?v])]
                 db)))))


(deftest rule-results-keep-bindings-that-differ-in-scale-or-sign
  (let [db (query/relation [[1 :a/v (cbor/decimal -1 10)] [1 :b/v (cbor/decimal -2 100)]
                            [2 :a/v pos-zero] [2 :b/v neg-zero]])
        rules '[[(val ?e ?v) [?e :a/v ?v]]
                [(val ?e ?v) [?e :b/v ?v]]]]
    (is (= 4 (qq '[:find (count-distinct ?v) . :in $ % :where (val ?e ?v)] db rules)))))


(deftest recursive-rules-tell-calls-apart-by-content
  ;; The cycle guard keys active rule calls by their arguments: a call on
  ;; 1.00M is not a repeat of the call on 1.0M, so the chain continues.
  (let [db (query/relation [[(cbor/decimal -1 10) :step/next (cbor/decimal -2 100)]
                            [(cbor/decimal -2 100) :step/next :end]])
        rules '[[(reach ?a ?b) [?a :step/next ?b]]
                [(reach ?a ?b) [?a :step/next ?c] (reach ?c ?b)]]
        reached (qq [:find '[?b ...] :in '$ '% :where (list 'reach (cbor/decimal -1 10) '?b)]
                    db rules)]
    (is (some #{:end} reached) (pr-str reached))
    (is (some #(same-content? (cbor/decimal -2 100) %) reached))))


(deftest the-returned-relation-is-still-a-host-set
  ;; The result keeps its type: a host set of tuples. Deduplication before
  ;; the set is kind-strict, but the set itself compares its members with
  ;; host =, so two tuples that are content-distinct yet host-= (and
  ;; host-hash-equal) are one member of it. This pins where each host draws
  ;; that line: the JVM merges both pairs, Dart merges native 0.0/-0.0, and
  ;; ClojureScript holds the carriers apart.
  (let [find-values #(qq '[:find ?v :where [_ :n/v ?v]] (query/relation %))
        decimals (find-values (take 2 scale-and-sign-rows))
        zeros (find-values (drop 2 scale-and-sign-rows))]
    (is (set? decimals))
    (is (= #?(:clj 1 :default 2) (count decimals)) (pr-str decimals))
    (is (= #?(:cljs 2 :default 1) (count zeros)) (pr-str zeros))))
