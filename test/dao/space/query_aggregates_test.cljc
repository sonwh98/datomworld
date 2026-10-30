(ns dao.space.query-aggregates-test
  "Phase 2 core language coverage: the aggregates distinct, median,
   variance, stddev and parameterized (min n ?x) / (max n ?x), plus the
   static unbound-find-variable refusal (E3) and the relation-slot refusal
   (E13). Every expectation here is deterministic and runs unchanged on the
   JVM, Node and Dart.

   Frozen conventions (docs/design/dao.space.query.datomic-compat.md,
   Functions and aggregates):
   - Aggregates fold the content-distinct rows of the grouped bindings
     projected to the :find, :with and aggregate-argument variables, so
     duplicates are eliminated unless :with distinguishes them.
   - Values are ordered by exact numeric order across every kind with the
     owner's tie rule (an exact operand before a float64, then the shorter
     canonical encoding), so results do not depend on the order rows arrive.
   - median: odd count -> the middle element itself (its kind kept); even
     count -> the double mean of the two middle elements.
   - variance: POPULATION variance (divide by n), a double; a single value
     gives 0.0. stddev is its square root.
   - (min n ?x) -> a vector of the n smallest ascending; (max n ?x) -> a
     vector of the n largest descending; n larger than the count returns
     everything; duplicates are kept (that is what :with controls).
   - distinct -> a set of the content-distinct values.
   - Floating point: values are converted to doubles and folded in the fixed
     sorted order; + - * / and sqrt are correctly rounded IEEE-754 operations
     on every host, so each result below is asserted for EXACT equality (no
     tolerance). The claim is limited to inputs these cases use (integers
     exactly representable as doubles); it is not a general promise of
     bit-identical float arithmetic.
   - Numeric carriers a host cannot evaluate are explicit refusals, not
     silent skips (the carrier cases below say which host refuses)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.space.query :as query]))


(def ^:private scores
  [[1 :s/g "a"] [1 :s/v 1] [2 :s/g "a"] [2 :s/v 2] [3 :s/g "a"] [3 :s/v 3]
   [4 :s/g "b"] [4 :s/v 4] [5 :s/g "b"] [5 :s/v 10] [6 :s/g "b"] [6 :s/v 4]])


(defn- db
  [facts]
  (query/fact-relation facts))


(defn- qq
  [form facts]
  (query/collect (query/q form (db facts))))


(defn- refusal
  "The ex-message f throws, or nil."
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-message e) (str e)))))


(defn- refused-with?
  "True when f throws and its message matches pattern. A boolean-valued
   helper on purpose: on ClojureScript, `(is (re-find re (str (f))))` reads the
   wrong value inside the cljs.test macro expansion (observed on Node), so a
   call is never wrapped in `str` inside an assertion form."
  [f pattern]
  (let [m (refusal f)]
    (boolean (and m (re-find pattern m)))))


(defn- same-content-vec?
  [a b]
  (and (= (count a) (count b)) (every? true? (map cbor/content= a b))))


;; ---------------------------------------------------------------------------
;; The new aggregates
;; ---------------------------------------------------------------------------

(deftest distinct-returns-the-content-distinct-values-as-a-set
  (is (= #{[#{1 2 3 4 10}]}
         (qq '[:find (distinct ?v) :where [?e :s/v ?v]] scores)))
  (testing ":with keeps the multiplicity of rows, but a set collapses it anyway"
    (is (= #{[#{1 2 3 4 10}]}
           (qq '[:find (distinct ?v) :with ?e :where [?e :s/v ?v]] scores))))
  (testing "grouping"
    (is (= #{["a" #{1 2 3}] ["b" #{4 10}]}
           (qq '[:find ?g (distinct ?v) :where [?e :s/g ?g] [?e :s/v ?v]] scores)))))


(deftest median-odd-even-and-grouped
  (testing "distinct rows: odd count returns the middle element itself"
    (is (= #{[3]} (qq '[:find (median ?v) :where [?e :s/v ?v]] scores))))
  (testing ":with keeps both 4s: even count returns the mean of the two middle values"
    (is (= #{[3.5]} (qq '[:find (median ?v) :with ?e :where [?e :s/v ?v]] scores))))
  (testing "grouped: group a is odd (kept as the integer 2), group b is even (7.0)"
    (is (= #{["a" 2] ["b" 7.0]}
           (qq '[:find ?g (median ?v) :where [?e :s/g ?g] [?e :s/v ?v]] scores))))
  (testing "a single value is its own median"
    (is (= #{[7]} (qq '[:find (median ?v) :where [?e :s/v ?v]] [[1 :s/v 7]])))))


(deftest variance-and-stddev-are-population-statistics
  (testing "exact, no tolerance: IEEE-754 operations in a fixed order"
    (is (= #{["a" 0.6666666666666666 0.816496580927726]
             ["b" 9.0 3.0]}
           (qq '[:find ?g (variance ?v) (stddev ?v) :where [?e :s/g ?g] [?e :s/v ?v]] scores)))
    (is (= #{[8.333333333333334 2.886751345948129]}
           (qq '[:find (variance ?v) (stddev ?v) :with ?e :where [?e :s/v ?v]] scores))))
  (testing "a single value has variance 0.0 and stddev 0.0"
    (is (= #{[0.0 0.0]}
           (qq '[:find (variance ?v) (stddev ?v) :where [?e :s/v ?v]] [[1 :s/v 7]])))))


(deftest parameterized-min-and-max
  (testing "n smallest ascending, n largest descending, over distinct rows"
    (is (= #{[[1 2] [10 4]]}
           (qq '[:find (min 2 ?v) (max 2 ?v) :where [?e :s/v ?v]] scores))))
  (testing ":with keeps the duplicate 4"
    (is (= #{[[1 2 3] [10 4 4]]}
           (qq '[:find (min 3 ?v) (max 3 ?v) :with ?e :where [?e :s/v ?v]] scores))))
  (testing "n larger than the count returns everything"
    (is (= #{[[10 4 4 3 2 1]]}
           (qq '[:find (max 10 ?v) :with ?e :where [?e :s/v ?v]] scores))))
  (testing "grouped"
    (is (= #{["a" [1 2]] ["b" [4 10]]}
           (qq '[:find ?g (min 2 ?v) :where [?e :s/g ?g] [?e :s/v ?v]] scores))))
  (testing "the unparameterized forms are unchanged"
    (is (= #{[1 10]} (qq '[:find (min ?v) (max ?v) :where [?e :s/v ?v]] scores)))))


(deftest a-non-positive-integer-parameter-yields-an-empty-vector
  (testing "ungrouped: the value is an empty vector in its normal result cell"
    (doseq [agg '[min max] n [0 -1 -100]]
      (is (= #{[[]]} (qq [:find (list agg n '?v) :where '[?e :s/v ?v]] scores))
          (str agg " " n))))
  (testing "grouped: every existing group gets an empty vector"
    (doseq [agg '[min max] n [0 -1]]
      (is (= #{["a" []] ["b" []]}
             (qq [:find '?g (list agg n '?v) :where '[?e :s/g ?g] '[?e :s/v ?v]] scores))
          (str agg " " n))))
  (testing "positive parameters keep the ordering and multiplicity rules"
    (is (= #{[[1 2]]} (qq '[:find (min 2 ?v) :where [?e :s/v ?v]] scores)))
    (is (= #{[[10 4]]} (qq '[:find (max 2 ?v) :where [?e :s/v ?v]] scores)))))


(deftest aggregate-arity-and-parameters-are-validated
  (doseq [form ['[:find (min 1.5 ?v) :where [?e :s/v ?v]]
                '[:find (max 1.5 ?v) :where [?e :s/v ?v]]
                '[:find (max :a ?v) :where [?e :s/v ?v]]
                '[:find (min :a ?v) :where [?e :s/v ?v]]
                '[:find (min 1 2 ?v) :where [?e :s/v ?v]]
                '[:find (median 2 ?v) :where [?e :s/v ?v]]
                '[:find (distinct 2 ?v) :where [?e :s/v ?v]]
                '[:find (count 2 ?v) :where [?e :s/v ?v]]
                '[:find (median) :where [?e :s/v ?v]]]]
    (is (some? (refusal #(qq form scores))) (pr-str form)))
  (testing "rand and sample are not aggregates here"
    (is (re-find #"Unknown aggregate" (refusal #(qq '[:find (rand ?v) :where [?e :s/v ?v]] scores))))
    (is (re-find #"Unknown aggregate" (refusal #(qq '[:find (sample 2 ?v) :where [?e :s/v ?v]] scores))))))


(deftest an-aggregate-argument-must-be-a-query-variable
  (doseq [form ['[:find (count [1 2]) :where [?e :s/v ?v]]
                '[:find (median 3) :where [?e :s/v ?v]]
                '[:find (min 2 [1]) :where [?e :s/v ?v]]
                '[:find (distinct "x") :where [?e :s/v ?v]]]]
    (is (refused-with? #(qq form scores) #"aggregate argument must be a query variable")
        (pr-str form))))


(deftest aggregates-over-empty-input-produce-no-row
  (doseq [agg '[median variance stddev distinct]]
    (is (= #{} (qq [:find (list agg '?v) :where '[?e :s/none ?v]] scores)) (str agg)))
  (is (= #{} (qq '[:find (min 2 ?v) :where [?e :s/none ?v]] scores))))


(deftest non-numeric-operands-are-refused-not-coerced
  (doseq [agg '[median variance stddev]]
    (is (re-find #"requires numeric operands"
                 (refusal #(qq [:find (list agg '?g) :where '[?e :s/g ?g]] scores)))
        (str agg)))
  (is (re-find #"requires numeric operands"
               (refusal #(qq '[:find (min 2 ?g) :where [?e :s/g ?g]] scores)))))


(deftest results-do-not-depend-on-the-order-rows-arrive
  (let [forms ['[:find (median ?v) (variance ?v) (stddev ?v) :with ?e :where [?e :s/v ?v]]
               '[:find (min 3 ?v) (max 3 ?v) :with ?e :where [?e :s/v ?v]]
               '[:find ?g (median ?v) :where [?e :s/g ?g] [?e :s/v ?v]]]]
    (doseq [form forms]
      (is (= (qq form scores) (qq form (vec (reverse scores))))
          (pr-str form)))))


;; ---------------------------------------------------------------------------
;; Kind ties: the order, and so the answer, is fixed across kinds and hosts
;; ---------------------------------------------------------------------------

(def ^:private ties
  [[1 :k/v 1] [2 :k/v (cbor/float64 1)] [3 :k/v 1] [4 :k/v (cbor/float64 2)]])


(deftest tied-kinds-order-exact-before-float
  (testing "an exact operand sorts before a float64 of the same value"
    (let [[[lo]] (seq (qq '[:find (min 3 ?v) :with ?e :where [?e :k/v ?v]] ties))]
      (is (same-content-vec? [1 1 (cbor/float64 1)] lo)))
    (let [[[hi]] (seq (qq '[:find (max 3 ?v) :with ?e :where [?e :k/v ?v]] ties))]
      (is (same-content-vec? [(cbor/float64 2) (cbor/float64 1) 1] hi))))
  (testing "median over [1 1 1.0 2.0] is the double mean of the middle pair (1 and 1.0)"
    ;; an even count needs arithmetic on the middle pair. 1.0 is a float64
    ;; CARRIER on JavaScript only (a Dart double and a JVM Double are native
    ;; numbers), so JavaScript refuses explicitly, exactly as every host does
    ;; for decimals
    (let [even-median #(qq '[:find (median ?v) :with ?e :where [?e :k/v ?v]] ties)]
      #?(:cljd (is (= #{[1.0]} (even-median)))
         :clj (is (= #{[1.0]} (even-median)))
         :cljs (is (re-find #"does not accept a numeric carrier" (refusal even-median))))))
  (testing "distinct keeps the tied-but-content-distinct members apart where the host set can"
    (let [[[s]] (seq (qq '[:find (distinct ?v) :where [?e :k/v ?v]] ties))]
      (is (= 3 (count s)) "1, 1.0 and 2.0 are three values")))
  (testing "an odd-count median keeps the kind of the middle element"
    (let [[[m]] (seq (qq '[:find (median ?v) :where [?e :k/v ?v]] ties))]
      ;; the distinct rows sort to [1 1.0 2.0]; the middle is the float 1.0
      (is (cbor/content= (cbor/float64 1) m)))))


#?(:cljd nil
   :clj
   (deftest the-jvm-set-collapse-of-tied-decimals-is-the-documented-limitation
     ;; E1 applies to a distinct set exactly as to the result set: 1.0M and
     ;; 1.00M are content-distinct but host-= on the JVM.
     (let [decimals [[1 :d/v (cbor/decimal -1 10)] [2 :d/v (cbor/decimal -2 100)]]
           [[s]] (seq (qq '[:find (distinct ?v) :where [?e :d/v ?v]] decimals))]
       (is (= 1 (count s))))))


;; ---------------------------------------------------------------------------
;; Numeric carriers: the host that cannot evaluate them refuses explicitly
;; ---------------------------------------------------------------------------

(def ^:private decimals
  [[1 :d/v (cbor/decimal -1 10)] [2 :d/v (cbor/decimal 0 3)] [3 :d/v (cbor/decimal 0 5)]])


(deftest carriers-in-order-statistics-work-everywhere
  (testing "median of an odd count needs no arithmetic and keeps the carrier"
    (let [[[m]] (seq (qq '[:find (median ?v) :where [?e :d/v ?v]] decimals))]
      (is (cbor/content= (cbor/decimal 0 3) m))))
  (testing "min/max n are order statistics: carriers are fine"
    (let [[[lo]] (seq (qq '[:find (min 2 ?v) :where [?e :d/v ?v]] decimals))]
      (is (same-content-vec? [(cbor/decimal -1 10) (cbor/decimal 0 3)] lo)))))


(defn carrier-refusals
  "On a host where decimals are carriers, arithmetic aggregates over them are
   explicit refusals. Public so the JVM reading of this file, which does not
   call it, has no unused-var noise."
  [variance even-median]
  (testing "this host has decimals only as carriers: arithmetic is an explicit refusal"
    (is (re-find #"does not accept a numeric carrier" (refusal variance)))
    (is (re-find #"does not accept a numeric carrier" (refusal even-median)))))


(deftest carriers-in-arithmetic-aggregates
  (let [variance #(qq '[:find (variance ?v) :where [?e :d/v ?v]] decimals)
        even-median #(qq '[:find (median ?v) :with ?e :where [?e :d/v ?v]]
                         (conj decimals [4 :d/v (cbor/decimal 0 7)]))]
    #?(:cljd (carrier-refusals variance even-median)
       :clj (testing "the JVM evaluates decimals as host numbers"
              (is (= #{[2.6666666666666665]} (variance)))
              (is (= #{[4.0]} (even-median)) "mean of the middle pair 3 and 5"))
       :cljs (carrier-refusals variance even-median))))


;; ---------------------------------------------------------------------------
;; E3: an unbound find variable is a static refusal
;; ---------------------------------------------------------------------------

(deftest unbound-find-variables-are-refused-before-evaluation
  (doseq [[label form] {"plain" '[:find ?z :where [?e :s/v ?v]]
                        "aggregate argument" '[:find (count ?z) :where [?e :s/v ?v]]
                        "with" '[:find ?v :with ?z :where [?e :s/v ?v]]
                        "pull variable" '[:find (pull ?z [:s/v]) :where [?e :s/v ?v]]
                        "tuple find" '[:find [?v ?z] :where [?e :s/v ?v]]
                        "only inside a not" '[:find ?w :where [?e :s/v ?v] (not-join [?e] [?e :s/g ?w])]}]
    (is (refused-with? #(qq form scores) #"not bound") label))
  (testing "refused with no evaluation: a function that would throw is never called"
    (let [called (volatile! false)]
      (is (refused-with? #(query/collect
                            (query/q '[:find ?z :where [?e :s/v ?v] [(boom ?v)]]
                                     (db scores)
                                     {:fns {'boom (fn [_] (vreset! called true) true)}}))
                         #"not bound"))
      (is (false? @called))))
  (testing "variables bound by :in, by patterns, by function outputs and by rule calls are fine"
    (is (= #{[1 "x"]}
           (query/collect (query/q '[:find ?v ?z :in $ ?z :where [1 :s/v ?v]] (db scores) "x"))))
    (is (= #{[1 1]}
           (query/collect (query/q '[:find ?v ?y :where [1 :s/v ?v] [(ground 1) ?y]] (db scores)))))
    (is (= #{[3 3]}
           (query/collect
             (query/q '[:find ?a ?b :in $ % :where (pair 2 ?a) [(identity ?a) ?b]]
                      (db scores)
                      '[[(pair ?x ?y) [(inc ?x) ?y]]]))))))


;; ---------------------------------------------------------------------------
;; E13: a relation slot must be a tuple source
;; ---------------------------------------------------------------------------

(deftest a-relation-slot-that-is-not-a-tuple-source-is-refused
  (let [q (fn [source] (query/collect (query/q '[:find ?e :where [?e :a _]] source)))]
    (testing "a delay of a non-sequence"
      (is (re-find #"sequence of tuples" (refusal #(q {:dao.space.query/relation (delay 5)})))))
    (testing "a non-sequence, non-delay"
      (is (re-find #"sequence of tuples" (refusal #(q {:dao.space.query/relation 5}))))
      (is (re-find #"sequence of tuples" (refusal #(q {:dao.space.query/relation {:a 1}})))))
    (testing "a delay of a valid tuple source, and a plain relation, still work"
      (is (= #{[1]} (q {:dao.space.query/relation (delay [[1 :a 2]])})))
      (is (= #{[1]} (q (query/relation [[1 :a 2]]))))
      (is (= #{} (q (query/relation [])))))
    (testing "through a view"
      (is (re-find #"sequence of tuples"
                   (refusal #(q (query/current {:dao.space.query/relation (delay 5)}))))))))
