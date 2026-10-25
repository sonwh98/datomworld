(ns dao.space.query.datomic-execution-test
  "Adapter-level bounded execution
   (docs/design/dao.space.query.datomic-compat.md, Normative execution
   bounds, Phase 4a): `:timeout`, the DaoSpace extension
   `:dao.space.query.datomic/max-steps`, the injected clock capability
   `:dao.space.query.datomic/now-fn`, fail-closed source admission, and the
   structured exhaustion errors.

   Deterministic: time comes from fake `now-fn`s keyed to observation
   counts, never from sleeps. One generously margined real-clock smoke runs
   on the JVM. The qualification is limited to materialized sources
   (relation / fact-relation and views over them); published-index paths
   are refused for bounded calls and are Phase 4b."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as core]
            [dao.space.query.datomic :as dq]))


(def ^:private facts
  [[1 :n 10] [2 :n 20] [3 :n 30] [4 :n 40]])


(defn- db
  []
  (core/fact-relation facts))


(def ^:private q1 '[:find ?e ?v :where [?e :n ?v] [(> ?v 15)]])


(defn- error-of
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-data e) [::other (str e)]))))


(def ^:private allowed-keys
  #{::dq/error :cognitect.anomalies/category :option :phase :steps :reason :variable
    :clause})


(defn- counter-clock
  "A now-fn whose reading is its own call count: 1, 2, 3, ... The counter
   is test scaffolding."
  []
  (let [n (volatile! 0)]
    {:now-fn (fn [] (vswap! n inc))
     :count n}))


;; ---------------------------------------------------------------------------
;; Option validation
;; ---------------------------------------------------------------------------

(deftest bounds-are-validated-before-evaluation
  (doseq [bad [0 -1 1.5 "5" nil :x]]
    (let [d (error-of #(dq/q {:query q1 :args [(db)] :timeout bad}))]
      (is (= :invalid-request (::dq/error d)) (pr-str [:timeout bad]))
      (is (= :timeout (:option d)))
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category d))))
    (let [d (error-of #(dq/q {:query q1 :args [(db)] ::dq/max-steps bad}))]
      (is (= :invalid-request (::dq/error d)) (pr-str [:max-steps bad]))
      (is (= ::dq/max-steps (:option d)))))
  (let [d (error-of #(dq/q {:query q1 :args [(db)] :timeout 5 ::dq/now-fn :not-a-fn}))]
    (is (= :invalid-request (::dq/error d)))
    (is (= ::dq/now-fn (:option d)))))


(deftest timeout-is-no-longer-refused-and-success-paths-are-unchanged
  (let [{:keys [now-fn]} (counter-clock)
        plain (dq/q q1 (db))]
    (is (= plain (dq/q {:query q1 :args [(db)] :timeout 1000000 ::dq/now-fn now-fn})))
    (is (= plain (dq/q {:query q1 :args [(db)] ::dq/max-steps 100000})))
    (is (= plain (dq/q {:query q1 :args [(db)] :timeout 1000000
                        ::dq/max-steps 100000 ::dq/now-fn now-fn})))
    (testing "offset/limit compose with bounds"
      (is (= 2 (count (dq/q {:query q1 :args [(db)] :limit 2 ::dq/max-steps 100000})))))))


(deftest bounded-results-match-unbounded-across-query-shapes
  (let [queries [['[:find ?n . :where [?e :n 20] [?e :n ?n]]]
                 ['[:find (count ?e) :where [?e :n _]]]
                 ['[:find (pull ?e [:n]) :where [?e :n 30]]]
                 ['[:find ?e :where (or [?e :n 10] [?e :n 40])]]
                 ['[:find ?e :where [?e :n _] (not [?e :n 10])]]
                 ['[:find ?x :in $ % :where (hop 1 ?x)]
                  '[[(hop ?a ?b) [?a :next ?b]] [(hop ?a ?b) [?a :next ?m] (hop ?m ?b)]]]]]
    (doseq [[query rules] queries]
      (let [src (core/fact-relation (into facts [[1 :next 2] [2 :next 3]]))
            args (cond-> [src] rules (conj rules))
            plain (apply dq/q query args)
            bounded (dq/q {:query query :args args ::dq/max-steps 1000000})]
        (is (= plain bounded) (pr-str query))))))


;; ---------------------------------------------------------------------------
;; Exhaustion errors
;; ---------------------------------------------------------------------------

(deftest work-exhaustion-is-a-structured-interrupted-error
  (let [d (error-of #(dq/q {:query q1 :args [(db)] ::dq/max-steps 3}))]
    (is (= :step-budget-exhausted (::dq/error d)))
    (is (= :cognitect.anomalies/interrupted (:cognitect.anomalies/category d)))
    (is (= ::dq/max-steps (:option d)))
    (is (contains? d :phase))
    (is (contains? d :steps))
    (is (every? allowed-keys (keys d)) (str "minimal ex-data only: " (pr-str (keys d))))))


(deftest deadline-expiry-is-a-structured-interrupted-error
  (let [clock (volatile! 0)
        d (error-of #(dq/q {:query q1 :args [(db)] :timeout 5
                            ::dq/now-fn (fn [] (let [t @clock] (vswap! clock + 100) t))}))]
    (is (= :timeout (::dq/error d)))
    (is (= :cognitect.anomalies/interrupted (:cognitect.anomalies/category d)))
    (is (= :timeout (:option d)))
    (is (every? allowed-keys (keys d)))
    (is (not-any? #(core/value? %) (vals d)) "no source handle in the error")))


(deftest no-partial-relation-is-ever-returned
  (let [n (fn [cap] (error-of #(dq/q {:query q1 :args [(db)] ::dq/max-steps cap})))
        ok (fn [cap]
             (try (dq/q {:query q1 :args [(db)] ::dq/max-steps cap})
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _ ::threw)))
        first-ok (first (filter #(not= ::threw (ok %)) (range 1 200)))]
    (is (some? first-ok))
    (doseq [cap (range 1 first-ok)]
      (is (= :step-budget-exhausted (::dq/error (n cap))) (str "cap " cap)))
    (is (= (dq/q q1 (db)) (ok first-ok)) "the exact boundary completes with the full answer")
    (is (= ::threw (ok (dec first-ok))) "one unit short never returns rows")))


(deftest the-phase-reports-where-the-allowance-ran-out
  (let [phases (into #{}
                     (map (fn [cap]
                            (:phase (error-of #(dq/q {:query q1 :args [(db)]
                                                      ::dq/max-steps cap})))))
                     (range 1 60))]
    (is (contains? phases :parse))
    (is (contains? phases :realize))
    (is (contains? phases :output) "output realization is accounted too")))


(deftest string-parsing-is-accounted
  (let [s "[:find ?e :where [?e :n 10]]"
        d (error-of #(dq/q {:query s :args [(db)] ::dq/max-steps 1}))]
    (is (= :step-budget-exhausted (::dq/error d)))
    (is (= :parse (:phase d)))))


;; ---------------------------------------------------------------------------
;; Stack safety: a generous allowance ends in structured exhaustion
;; ---------------------------------------------------------------------------

(deftest generous-allowance-on-fresh-value-recursion-is-structured-exhaustion
  (let [rules '[[(up ?n) [(inc ?n) ?m] (up ?m)]]
        q '[:find ?x :in $ % :where (up 0) [?x :n _]]]
    (doseq [cap [1000 20000]]
      (let [d (error-of #(dq/q {:query q :args [(db) rules] ::dq/max-steps cap}))]
        (is (= :step-budget-exhausted (::dq/error d)) (str "cap " cap))
        (is (every? allowed-keys (keys d)))))))


;; ---------------------------------------------------------------------------
;; Function-result binding is accounted per attempted binding
;; ---------------------------------------------------------------------------

(def ^:private escape-q
  '[:find ?x :in $ ?xs :where [(identity ?xs) [0 ...]] [(ground 1) ?x]])


(deftest function-result-candidates-are-charged-through-the-adapter
  (testing "ten candidates complete within a small cap"
    (is (= [] (dq/q {:query escape-q :args [(db) (vec (range 1 11))] ::dq/max-steps 100}))))
  (testing "ten thousand candidates exhaust the same cap, structured"
    (let [d (error-of #(dq/q {:query escape-q :args [(db) (vec (range 1 10001))]
                              ::dq/max-steps 100}))]
      (is (= :step-budget-exhausted (::dq/error d)))
      (is (every? allowed-keys (keys d)))))
  (testing "an infinite collection argument is not enumerated ahead of the budget"
    (let [d (error-of #(dq/q {:query '[:find ?x :in $ [?x ...] :where [(ground 1) ?y]]
                              :args [(db) (iterate inc 0)] ::dq/max-steps 100}))]
      (is (= :step-budget-exhausted (::dq/error d))))))


;; ---------------------------------------------------------------------------
;; Deep source chains and deep syntax: structured, never a host overflow
;; ---------------------------------------------------------------------------

(declare published)


(defn- deep-view
  "A chain of n nested current views over `base`, built through the public
   API."
  [base n]
  (reduce (fn [s _] (core/current s)) base (range n)))


(def ^:private eq '[:find ?e :where [?e :a ?v]])


(deftest deep-materialized-source-chains-are-accounted-not-overflowed
  (let [source (deep-view (core/relation []) 10000)]
    (testing "a small allowance is structured exhaustion during admission"
      (let [d (error-of #(dq/q {:query eq :args [source] ::dq/max-steps 100}))]
        (is (= :step-budget-exhausted (::dq/error d)))
        (is (= :parse (:phase d)))))
    (testing "a generous allowance admits and realizes the chain iteratively"
      (is (= [] (dq/q {:query eq :args [source] ::dq/max-steps 100000000}))))
    (testing "a small deadline-free step cap in the middle of realization is also structured"
      (let [d (error-of #(dq/q {:query eq :args [source] ::dq/max-steps 10050}))]
        (is (= :step-budget-exhausted (::dq/error d)))))
    (testing "unbounded calls realize the same chain without a stack overflow"
      (is (= [] (dq/q eq source))))))


(deftest deep-chains-over-a-published-terminal-are-refused-with-zero-reads
  (let [reads (volatile! 0)
        source (deep-view (published reads) 10000)]
    (testing "a generous allowance walks the chain and refuses the published terminal"
      (let [d (error-of #(dq/q {:query eq :args [source] ::dq/max-steps 100000000}))]
        (is (= :unsupported (::dq/error d)))
        (is (= :bounded-source-unqualified (:reason d)))))
    (testing "a small allowance is exhausted first, still structured"
      (is (= :step-budget-exhausted
             (::dq/error (error-of #(dq/q {:query eq :args [source] ::dq/max-steps 100}))))))
    (is (zero? @reads) "no read in either case")))


(def ^:private deep-limit dq/max-syntax-depth)


(defn- nest-not
  [n]
  (reduce (fn [c _] (list 'not c)) '[?e :n _] (range n)))


(deftest native-syntax-depth-is-bounded-by-a-documented-constant
  (let [src (db)
        wrap (fn [clause] (into '[:find ?e :where [?e :n _]] [clause]))]
    (testing "the constant is documented data"
      (is (pos? deep-limit)))
    (testing "over-deep negation nesting is refused before any recursion"
      (let [d (error-of #(dq/q (wrap (nest-not (* 20 deep-limit))) src))]
        (is (= :unsupported (::dq/error d)))
        (is (= :syntax-depth-limit (:reason d)))))
    (testing "a deep pull pattern is refused too"
      (let [pat (reduce (fn [p _] [{:a p}]) [:n] (range (* 20 deep-limit)))
            d (error-of #(dq/q [:find (list 'pull '?e pat) :where ['?e :n '_]] src))]
        (is (= :syntax-depth-limit (:reason d)))))
    (testing "over-deep rules are refused"
      (let [rule (vector (list 'r '?e) (nest-not (* 20 deep-limit)))
            d (error-of (fn [] (dq/q '[:find ?e :in $ % :where (r ?e)] src [rule])))]
        (is (= :syntax-depth-limit (:reason d)))))
    (testing "nesting inside the limit still evaluates"
      (is (vector? (dq/q (wrap (nest-not 6)) src))))
    (testing "bounded calls get the same refusal"
      (let [d (error-of #(dq/q {:query (wrap (nest-not (* 20 deep-limit))) :args [src]
                                ::dq/max-steps 1000000}))]
        (is (= :syntax-depth-limit (:reason d)))))))


;; ---------------------------------------------------------------------------
;; The clock: minted at entry, expiry at now >= deadline
;; ---------------------------------------------------------------------------

(deftest the-deadline-is-minted-at-entry-before-parsing
  (let [{:keys [now-fn count]} (counter-clock)
        d (error-of #(dq/q {:query "[:find" :args [(db)] :timeout 1000 ::dq/now-fn now-fn}))]
    (is (= :invalid-request (::dq/error d)) "the unreadable query is refused")
    (is (pos? @count) "the clock was read at entry; the parse failure came after it")))


(deftest exact-boundary-through-the-adapter
  (let [{:keys [now-fn count]} (counter-clock)
        plain (dq/q q1 (db))
        _ (dq/q {:query q1 :args [(db)] :timeout 1000000 ::dq/now-fn now-fn})
        m @count  ; mint + every later observation
        at (fn [timeout]
             (let [{:keys [now-fn count]} (counter-clock)]
               [(error-of #(dq/q {:query q1 :args [(db)] :timeout timeout ::dq/now-fn now-fn}))
                @count]))]
    (is (> m 2))
    (testing "the mint reads 1, so deadline = 1 + timeout; the m-th observation reads m"
      (is (= ::none (first (at m))) "deadline m+1: the last observation is live")
      (let [[d n] (at (dec m))]
        (is (= :timeout (::dq/error d)) "deadline m: expiry AT the final observation")
        (is (= m n) "no observation after expiry"))
      (let [[d n] (at (- m 2))]
        (is (= :timeout (::dq/error d)))
        (is (= (dec m) n))))
    (is (= plain (dq/q {:query q1 :args [(db)] :timeout (inc m) ::dq/now-fn now-fn})))))


(deftest an-adapter-call-without-bounds-reads-no-clock
  (let [{:keys [now-fn count]} (counter-clock)]
    (is (= (dq/q q1 (db))
           (dq/q {:query q1 :args [(db)] ::dq/now-fn now-fn})))
    (is (zero? @count) "no timeout, no clock reading")))


(deftest real-clock-smoke
  (testing "the default clock reads a finite, non-decreasing number on every host"
    (let [t1 (dq/default-now-ms)
          t2 (dq/default-now-ms)]
      (is (number? t1))
      (is (<= t1 t2))
      (is (< (- t2 t1) 5000) "two adjacent readings are far closer than the 5s margin")))
  (testing "a generous deadline passes on the default clock"
    (is (= (dq/q q1 (db)) (dq/q {:query q1 :args [(db)] :timeout 60000})))))


#?(:cljd nil
   :clj
   (deftest real-clock-smoke-jvm-monotonic
     (let [t0 (quot (System/nanoTime) 1000000)
           r (dq/q {:query q1 :args [(db)] :timeout 60000})]
       (is (seq r))
       (is (< (- (quot (System/nanoTime) 1000000) t0) 5000) "a 5s+ margin"))))


;; ---------------------------------------------------------------------------
;; Fail-closed source admission (zero source reads)
;; ---------------------------------------------------------------------------

(defn- published
  "A published-index descriptor whose rows record every read."
  [reads]
  {:dao.space.query/published :index
   :rows (delay (vswap! reads inc) [])})


(defn- deferred
  [reads]
  {:dao.space.query/relation (delay (vswap! reads inc) facts)})


(def ^:private bound-options
  [{:timeout 1000000 ::dq/now-fn (constantly 0)}
   {::dq/max-steps 1000000}
   {:timeout 1000000 ::dq/max-steps 1000000 ::dq/now-fn (constantly 0)}])


(defn- refused-unqualified?
  [d]
  (and (map? d)
       (= :unsupported (::dq/error d))
       (= :cognitect.anomalies/unsupported (:cognitect.anomalies/category d))
       (= :bounded-source-unqualified (:reason d))
       (every? allowed-keys (keys d))))


(deftest bounded-calls-refuse-published-and-deferred-sources-with-zero-reads
  (doseq [opts bound-options]
    (testing (pr-str (keys opts))
      (let [reads (volatile! 0)]
        (testing "direct published index"
          (let [d (error-of #(dq/q (merge {:query q1 :args [(published reads)]} opts)))]
            (is (refused-unqualified? d))
            (is (= "$" (:variable d)))))
        (testing "wrapped: a current view over a published index"
          (is (refused-unqualified?
                (error-of #(dq/q (merge {:query q1 :args [(core/current (published reads))]}
                                        opts))))))
        (testing "wrapped twice"
          (is (refused-unqualified?
                (error-of #(dq/q (merge {:query q1
                                         :args [(core/current (core/current (published reads)))]}
                                        opts))))))
        (testing "deferred source rows"
          (is (refused-unqualified?
                (error-of #(dq/q (merge {:query q1 :args [(deferred reads)]} opts))))))
        (testing "a view over deferred rows"
          (is (refused-unqualified?
                (error-of #(dq/q (merge {:query q1 :args [(core/current (deferred reads))]}
                                        opts))))))
        (testing "cached: a warmed published index is still not a qualification certificate"
          (let [warm (published reads)
                _ (force (:rows warm))
                _ (vreset! reads 0)]
            (is (refused-unqualified?
                  (error-of #(dq/q (merge {:query q1 :args [warm]} opts)))))
            (is (zero? @reads))))
        (testing "mixed: every declared source is checked, including an unused one"
          (let [mixed '[:find ?e :in $a $b :where [$a ?e :n _]]]
            (is (refused-unqualified?
                  (error-of #(dq/q (merge {:query mixed :args [(db) (published reads)]} opts)))))
            (is (refused-unqualified?
                  (error-of #(dq/q (merge {:query mixed :args [(published reads) (db)]} opts)))))
            (let [d (error-of #(dq/q (merge {:query mixed :args [(db) (published reads)]} opts)))]
              (is (= "$b" (:variable d))))))
        (is (zero? @reads) "no source was read, forced or realized by any refusal")))))


(deftest unbounded-calls-keep-their-source-support
  (let [reads (volatile! 0)]
    (is (= (dq/q q1 (db))
           (dq/q q1 (deferred reads)))
        "a deferred relation still works without bounds")
    (is (pos? @reads) "and is forced, as before")))


(deftest bounded-calls-admit-materialized-sources-and-their-views
  (let [d5 (mapv #(conj % 1 1) facts)
        view (core/current (core/relation d5))
        history (core/history (core/relation d5))
        opts {::dq/max-steps 1000000}]
    (is (= [[2 20] [3 30] [4 40]]
           (sort (dq/q (merge {:query q1 :args [(db)]} opts)))))
    (is (= (sort (dq/q q1 view)) (sort (dq/q (merge {:query q1 :args [view]} opts)))))
    (is (vector? (dq/q (merge {:query '[:find ?e :where [?e :n _]] :args [history]} opts)))
        "a history view over a relation is admitted (source admission only)")))


;; ---------------------------------------------------------------------------
;; Ownership and statelessness
;; ---------------------------------------------------------------------------

(deftest bounded-calls-borrow-their-sources
  (let [src (db)
        before (pr-str src)]
    (dq/q {:query q1 :args [src] ::dq/max-steps 100000})
    (error-of #(dq/q {:query q1 :args [src] ::dq/max-steps 3}))
    (error-of #(dq/q {:query q1 :args [src] :timeout 5 ::dq/now-fn (let [n (volatile! 0)]
                                                                     (fn [] (vswap! n + 10)))}))
    (is (= before (pr-str src)) "inputs are neither opened, closed nor changed")
    (is (= (dq/q q1 src) (dq/q q1 src)) "no hidden state between calls")))
