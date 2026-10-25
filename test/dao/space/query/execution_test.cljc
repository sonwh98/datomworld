(ns dao.space.query.execution-test
  "Deterministic tests of the generic bounded-execution machinery
   (docs/design/dao.space.query.datomic-compat.md, Normative execution
   bounds and Deterministic testing).

   A fake driver expires after a chosen number of observations; no sleeps,
   no scheduler timing. Scope note (Phase 4): these tests prove the
   machinery in isolation, including the one-in-flight read guarantee over
   an explicit read-step sequence, and then the INTEGRATED bounded `q`
   (`dao.space.query/q-with-state`, the one evaluation kernel): operation
   accounting, state across sibling branches and nested negation/rules,
   rule-scope restoration without progress rollback, first-witness
   termination, exhaustion by rejected candidates and fresh-value recursion,
   checked realization, input expansion, pull and aggregation, and
   deterministic deadline expiry. They do NOT certify B-tree traversal:
   published indexes are refused for bounded calls (Phase 4b)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as core]
            [dao.space.query.execution :as ex]))


(defn- counting-driver
  "A driver whose clock reads `base` for the first `live` observations and
   `base + 1000` afterwards, plus a log of how many observations were taken.
   The counter is test-local scaffolding, not invocation state."
  [deadline live]
  (let [n (volatile! 0)]
    {:driver (ex/fn-driver (fn []
                             (vswap! n inc)
                             (if (<= @n live) (- deadline 10) (+ deadline 1000))))
     :count n}))


;; ---------------------------------------------------------------------------
;; The published operation-to-unit table
;; ---------------------------------------------------------------------------

(deftest the-operation-to-unit-table-is-published
  (is (= {:candidate 1 :scan-advance 1 :rule-entry 1 :aggregate-input 1
          :pull-advance 1 :tree-step 1 :node-read 1 :parse-step 1 :output-row 1}
         ex/step-units)
      "every accountable operation and its unit cost")
  (is (= [:parse :realize :plan :join :nested :recursion :aggregate :pull :output :read]
         ex/phases)
      "phase boundaries observed at entry and exit, plus :read (the storage-read bracket)"))


;; ---------------------------------------------------------------------------
;; Work allowance, independent of time
;; ---------------------------------------------------------------------------

(deftest work-allowance-boundaries
  (testing "immediately before the boundary"
    (let [s (reduce ex/charge (ex/start {:max-steps 3}) [:candidate :candidate])]
      (is (not (ex/terminal? s)))
      (is (= 2 (ex/steps s)))))
  (testing "exact boundary: the last allowed unit succeeds"
    (let [s (reduce ex/charge (ex/start {:max-steps 3}) [:candidate :candidate :candidate])]
      (is (not (ex/terminal? s)))
      (is (= 3 (ex/steps s)))))
  (testing "immediately after: refused before performing the unit"
    (let [s (reduce ex/charge (ex/start {:max-steps 3}) (repeat 4 :candidate))]
      (is (= :step-budget-exhausted (:kind (ex/outcome s))))
      (is (= 3 (:steps (ex/outcome s))) "the refused unit is not consumed")))
  (testing "rejected and retained candidates cost the same"
    (is (= 2 (ex/steps (reduce ex/charge (ex/start {:max-steps 9}) [:candidate :candidate])))))
  (testing "every table entry is chargeable"
    (doseq [op (keys ex/step-units)]
      (is (= 1 (ex/steps (ex/charge (ex/start {:max-steps 2}) op))) (str op))))
  (testing "no cap means unbounded, with no ambient default"
    (let [s (reduce ex/charge ex/unbounded (repeat 1000 :scan-advance))]
      (is (not (ex/terminal? s)))
      (is (= 1000 (ex/steps s))))))


(deftest work-exhaustion-never-reads-the-clock
  (let [{:keys [driver count]} (counting-driver 100 100)
        s (reduce ex/charge (ex/start {:max-steps 1 :deadline 100}) [:candidate :candidate])]
    (is (= :step-budget-exhausted (:kind (ex/outcome s))))
    (is (zero? @count) "charge takes no observation")
    (is (some? driver))))


(deftest invalid-allowances-are-refused
  (doseq [bad [0 -1 1.5 "3" :x]]
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (ex/start {:max-steps bad}))
        (pr-str bad)))
  (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
        (ex/charge ex/unbounded :no-such-op))))


;; ---------------------------------------------------------------------------
;; Deadline checkpoints with a fake driver
;; ---------------------------------------------------------------------------

(deftest deadline-expiry-after-n-observations
  (doseq [live [0 1 2 3]]
    (let [{:keys [driver]} (counting-driver 500 live)
          states (reductions (fn [s ph] (ex/checkpoint s driver ph))
                             (ex/start {:deadline 500})
                             [:parse :realize :plan :join])
          first-expired (first (keep-indexed (fn [i s] (when (ex/terminal? s) i)) states))]
      (is (= (inc live) first-expired)
          (str "expires on observation " (inc live) " when " live " are live"))))
  (testing "the outcome names the phase and a minimal projection"
    (let [{:keys [driver]} (counting-driver 500 1)
          s (-> (ex/start {:deadline 500})
                (ex/checkpoint driver :parse)
                (ex/checkpoint driver :realize))]
      (is (= {:kind :timeout :phase :realize :steps 0} (ex/outcome s))))))


(deftest exact-deadline-boundary
  (let [at (fn [t] (ex/fn-driver (constantly t)))
        s (ex/start {:deadline 500})]
    (is (not (ex/terminal? (ex/checkpoint s (at 499) :join))) "immediately before")
    (is (= :timeout (:kind (ex/outcome (ex/checkpoint s (at 500) :join)))) "at the deadline")
    (is (= :timeout (:kind (ex/outcome (ex/checkpoint s (at 501) :join)))) "immediately after")))


(deftest no-deadline-means-no-observation
  (let [{:keys [driver count]} (counting-driver 0 0)
        s (reduce (fn [s ph] (ex/checkpoint s driver ph)) ex/unbounded ex/phases)]
    (is (not (ex/terminal? s)))
    (is (zero? @count) "an unbounded policy never reads a clock")))


(deftest no-observation-after-terminal
  (let [{:keys [driver count]} (counting-driver 500 0)
        s1 (ex/checkpoint (ex/start {:deadline 500}) driver :parse)
        n @count
        s2 (-> s1 (ex/checkpoint driver :plan) (ex/charge :candidate) (ex/finish driver))]
    (is (ex/terminal? s1))
    (is (= n @count) "later checkpoints do not touch the driver")
    (is (= (ex/outcome s1) (ex/outcome s2)) "the terminal outcome is stable")
    (is (= (ex/steps s1) (ex/steps s2)) "no further work is accounted")))


(deftest work-and-time-are-independent
  (testing "time expiry with allowance left"
    (let [{:keys [driver]} (counting-driver 500 0)
          s (ex/checkpoint (ex/start {:deadline 500 :max-steps 99}) driver :join)]
      (is (= :timeout (:kind (ex/outcome s))))))
  (testing "work exhaustion with time left"
    (let [{:keys [driver]} (counting-driver 500 99)
          s (-> (ex/start {:deadline 500 :max-steps 1})
                (ex/charge :candidate) (ex/charge :candidate)
                (ex/checkpoint driver :join))]
      (is (= :step-budget-exhausted (:kind (ex/outcome s)))))))


(deftest the-state-is-immutable-and-threaded
  (let [s0 (ex/start {:max-steps 2})
        s1 (ex/charge s0 :candidate)
        s2 (ex/charge s0 :candidate)]
    (is (= 0 (ex/steps s0)) "charging returns a new state")
    (testing "forking one state into two branches does not share progress"
      (is (= s1 s2))
      (is (= 2 (ex/steps (ex/charge s1 :candidate)))
          "only the state returned by prior work carries the progress")
      (is (not (ex/terminal? (ex/charge s2 :candidate)))))
    (testing "threaded through two branches the allowance is shared"
      (is (= :step-budget-exhausted
             (:kind (ex/outcome (-> s0 (ex/charge :candidate) (ex/charge :candidate)
                                    (ex/charge :candidate)))))))))


;; ---------------------------------------------------------------------------
;; The one-in-flight read guarantee
;; ---------------------------------------------------------------------------

(defn- recording-store
  "Read thunks that log which read ran, with the clock advancing past the
   deadline during read number `slow`. Test-local scaffolding."
  [n slow deadline]
  (let [log (volatile! [])
        clock (volatile! 0)
        driver (ex/fn-driver (fn [] @clock))
        reads (mapv (fn [i]
                      (fn []
                        (vswap! log conj i)
                        (when (= i slow) (vreset! clock (+ deadline 1)))
                        i))
                    (range n))]
    {:driver driver :reads reads :log log}))


(deftest at-most-one-read-runs-past-the-deadline
  (testing "expiry during read 2: that read completes, reads 3.. never start"
    (let [{:keys [driver reads log]} (recording-store 6 2 100)
          [s vals] (ex/run-reads (ex/start {:deadline 100}) driver reads)]
      (is (= :timeout (:kind (ex/outcome s))))
      (is (= [0 1 2] @log) "exactly one in-flight read beyond the deadline")
      (is (= [0 1] vals) "no partial value from the overshooting read is delivered")))
  (testing "expiry before the first read: no read starts"
    (let [{:keys [driver reads log]} (recording-store 3 -1 100)
          start (ex/start {:deadline 0})
          [s _] (ex/run-reads start driver reads)]
      (is (= :timeout (:kind (ex/outcome s))))
      (is (empty? @log) "an expired state starts no read")))
  (testing "no expiry: every read runs"
    (let [{:keys [driver reads log]} (recording-store 4 -1 100)
          [s vals] (ex/run-reads (ex/start {:deadline 100}) driver reads)]
      (is (not (ex/terminal? s)))
      (is (= [0 1 2 3] @log vals))))
  (testing "a terminal state never starts a read"
    (let [{:keys [driver reads log]} (recording-store 2 -1 100)
          dead (ex/charge (ex/charge (ex/start {:max-steps 1}) :candidate) :candidate)
          [s _] (ex/run-reads dead driver reads)]
      (is (= :step-budget-exhausted (:kind (ex/outcome s))))
      (is (empty? @log)))))


(deftest ownership-is-not-touched
  (testing "the machinery only reads the values it is handed"
    (let [source {:dao.space.query/relation [[1 :a 2]]}
          before (pr-str source)
          {:keys [driver]} (counting-driver 500 0)
          _ (ex/finish (ex/start {:deadline 500}) driver)
          _ (ex/run-reads (ex/start {:deadline 500}) driver [(fn [] source)])]
      (is (= before (pr-str source)) "inputs are neither opened, closed nor changed"))))


;; ---------------------------------------------------------------------------
;; One real-clock smoke (JVM only; generous margin; no semantic decisions)
;; ---------------------------------------------------------------------------

#?(:cljd nil
   :clj
   (deftest real-clock-smoke
     (let [driver (ex/fn-driver #(quot (System/nanoTime) 1000000))
           now (quot (System/nanoTime) 1000000)]
       (is (not (ex/terminal? (ex/checkpoint (ex/start {:deadline (+ now 5000)})
                                             driver :join)))
           "a deadline 5s ahead has not expired")
       (is (= :timeout (:kind (ex/outcome (ex/checkpoint (ex/start {:deadline (- now 5000)})
                                                         driver :join))))
           "a deadline 5s behind has expired"))))


;; ---------------------------------------------------------------------------
;; Machinery corrections (architect ruling, section 3)
;; ---------------------------------------------------------------------------

(deftest terminal-states-are-fully-absorbing
  (let [{:keys [driver count]} (counting-driver 500 0)
        s1 (ex/checkpoint (ex/start {:deadline 500}) driver :parse)
        n @count]
    (is (ex/terminal? s1))
    (is (= s1 (ex/checkpoint s1 driver :plan)) "the whole state, phase included")
    (is (= s1 (ex/step s1 driver :candidate)))
    (is (= s1 (ex/charge s1 :candidate)))
    (is (= s1 (ex/enter s1 :output)))
    (is (= s1 (ex/finish s1 driver)))
    (is (= n @count) "no later operation touches the driver")))


(deftest step-observes-then-charges
  (testing "an expired deadline is observed before the unit is charged"
    (let [{:keys [driver]} (counting-driver 500 0)
          s (ex/step (ex/start {:deadline 500 :max-steps 5}) driver :candidate)]
      (is (= :timeout (:kind (ex/outcome s))))
      (is (zero? (ex/steps s)) "the unit was not consumed")))
  (testing "deadline-versus-budget precedence is reproducible"
    (let [{:keys [driver]} (counting-driver 500 0)
          s (ex/step (ex/start {:deadline 500 :max-steps 1}) driver :candidate)
          t (ex/step (assoc s ::unused 1) driver :candidate)]
      (is (= :timeout (:kind (ex/outcome t))) "the first terminal outcome wins permanently")))
  (testing "a live observation then a refused charge is exhaustion"
    (let [{:keys [driver]} (counting-driver 500 9)
          s (-> (ex/start {:deadline 500 :max-steps 1})
                (ex/step driver :candidate)
                (ex/step driver :candidate))]
      (is (= :step-budget-exhausted (:kind (ex/outcome s)))))))


(deftest read-step-discards-a-value-whose-post-read-observation-expired
  (let [clock (volatile! 0)
        driver (ex/fn-driver (fn [] @clock))
        [s v] (ex/read-step (ex/start {:deadline 100}) driver
                            (fn [] (vreset! clock 101) :the-value))]
    (is (= :timeout (:kind (ex/outcome s))))
    (is (= ::ex/discarded v) "the primitive itself withholds the value")
    (is (not= :the-value v)))
  (testing "no read starts from an expired state"
    (let [driver (ex/fn-driver (constantly 101))
          ran (volatile! false)
          [s v] (ex/read-step (ex/start {:deadline 100}) driver (fn [] (vreset! ran true)))]
      (is (ex/terminal? s))
      (is (= ::ex/not-read v))
      (is (false? @ran)))))


;; ===========================================================================
;; Integrated bounded q (the common kernel under a real execution state)
;; ===========================================================================

(def ^:private three [[1 :n 10] [2 :n 20] [3 :n 30]])


(defn- run-q
  "[state result] of the kernel for a query under (ex/start opts)."
  [opts driver query & inputs]
  (apply core/q-with-state (ex/start opts) driver query inputs))


(defn- steps-of
  "The unbounded step count of a query (the operation accounting, exactly)."
  [query & inputs]
  (let [[st r] (apply core/q-with-state ex/unbounded nil query inputs)]
    (is (some? r))
    (ex/steps st)))


(defn- exhausts?
  "True when the query under a work cap of n is terminal and exposes no
   result."
  [n query & inputs]
  (let [[st r] (apply run-q {:max-steps n} nil query inputs)]
    (and (= :step-budget-exhausted (:kind (ex/outcome st)))
         (nil? r))))


(defn- completes?
  [n query & inputs]
  (let [[st r] (apply run-q {:max-steps n} nil query inputs)]
    (and (not (ex/terminal? st)) (some? r))))


(defn- rows-of
  [res]
  (core/collect res))


(deftest the-operation-to-unit-accounting-table-over-real-queries
  (let [facts (core/fact-relation three)
        plain (core/relation three)]
    (testing "fact-relation: parse 4 + index build 3 + init binding 1 + scan 3 + candidates 3 + output 3"
      (is (= 17 (steps-of '[:find ?e :where [?e :n ?v]] facts))))
    (testing "plain relation: no index build; each tuple is a scan unit and a candidate"
      (is (= 14 (steps-of '[:find ?e :where [?e :n ?v]] plain))))
    (testing "an empty scan costs only parse, realization and the initial binding"
      (is (= 8 (steps-of '[:find ?e :where [?e :zzz ?v]] facts))))
    (testing "a second clause is charged per binding that reaches it"
      (is (= 25 (steps-of '[:find ?e ?w :where [?e :n ?v] [?e :n ?w]] facts))))
    (testing "aggregate inputs and outputs are charged"
      (is (= 18 (steps-of '[:find (count ?e) :where [?e :n ?v]] facts))))
    (testing "pull advances are charged"
      (is (= 23 (steps-of '[:find (pull ?e [*]) :where [?e :n ?v]] facts))))
    (testing "input expansion: one candidate per produced binding (the init binding and each pair), no pre-count"
      (is (= 17 (steps-of '[:find ?e :in $ [[?e ?x]] :where [?e :n ?v]]
                          facts [[1 2] [3 4]]))))))


(deftest bounded-results-equal-unbounded-results
  (let [facts (core/fact-relation three)
        q '[:find ?e ?v :where [?e :n ?v] [(> ?v 15)]]
        [st r] (run-q {:max-steps 10000} nil q facts)]
    (is (not (ex/terminal? st)))
    (is (= (rows-of (core/q q facts)) (rows-of r)))))


(deftest work-allowance-exact-boundary-on-a-real-query
  (let [facts (core/fact-relation three)
        q '[:find ?e :where [?e :n ?v]]
        n (steps-of q facts)]
    (is (completes? n q facts) "exact boundary: the last permitted unit executes")
    (is (exhausts? (dec n) q facts) "one unit short: terminal, no partial relation")
    (is (completes? (inc n) q facts))
    (let [[st _] (run-q {:max-steps (dec n)} nil q facts)]
      (is (= (dec n) (:steps (ex/outcome st))) "the refused unit is not consumed"))))


(deftest rejected-candidate-scans-exhaust-the-cap
  (let [many (core/fact-relation (mapv (fn [i] [i :a i]) (range 200)))
        q '[:find ?e :where [?e :a ?v] [(> ?v 100000)]]]
    (is (empty? (rows-of (core/q q many))) "every candidate is rejected")
    (is (exhausts? 50 q many) "rejection still consumes work")
    (is (completes? (steps-of q many) q many))))


(deftest empty-result-scans-are-charged
  (let [many (core/relation (mapv (fn [i] [i :a i]) (range 200)))
        q '[:find ?e :where [?e :a 12345]]]
    (is (exhausts? 40 q many) "a scan that binds nothing cannot bypass the bound")))


(def ^:private chain-rules
  '[[(reach ?a ?b) [?a :edge ?b]]
    [(reach ?a ?b) [?a :edge ?m] (reach ?m ?b)]])


(def ^:private edges
  (core/fact-relation [[1 :edge 2] [2 :edge 3] [3 :edge 4]]))


(deftest state-accumulates-across-sibling-branches
  (let [q '[:find ?e :where (or [?e :n 10] [?e :n 20])]
        facts (core/fact-relation three)
        both (steps-of q facts)
        left (steps-of '[:find ?e :where [?e :n 10]] facts)
        right (steps-of '[:find ?e :where [?e :n 20]] facts)]
    (is (> both left))
    (is (> both right))
    (is (completes? both q facts))
    (is (exhausts? (dec both) q facts)
        "each branch received the state its sibling returned: no branch restarted the allowance")))


(deftest state-accumulates-through-nested-negation-and-rules
  (let [facts (core/fact-relation three)
        q '[:find ?e :where [?e :n _] (not-join [?e] [?e :n ?v] [(> ?v 15)])]
        n (steps-of q facts)]
    (is (completes? n q facts))
    (is (exhausts? (dec n) q facts) "work inside a negation is charged to the caller's state")
    (is (= #{[1]} (rows-of (second (run-q {:max-steps n} nil q facts))))))
  (let [q '[:find ?b :in $ % :where (reach 1 ?b)]
        n (steps-of q edges chain-rules)]
    (is (= #{[2] [3] [4]} (rows-of (second (run-q {:max-steps n} nil q edges chain-rules)))))
    (is (exhausts? (dec n) q edges chain-rules) "nested rule expansion shares one allowance")))


(deftest active-rule-scope-is-restored-without-rolling-progress-back
  (let [one '[:find ?x :in $ % :where (reach 1 ?x)]
        two '[:find ?x ?y :in $ % :where (reach 1 ?x) (reach 1 ?y)]
        n1 (steps-of one edges chain-rules)
        n2 (steps-of two edges chain-rules)]
    (is (= #{[2 2] [2 3] [2 4] [3 2] [3 3] [3 4] [4 2] [4 3] [4 4]}
           (rows-of (core/q two edges chain-rules)))
        "the second sibling call of the same rule is not blocked by the first call's active key")
    (is (> n2 (* 2 n1)) "the second call is charged on top of the progress the first returned")
    (is (exhausts? (dec n2) two edges chain-rules))))


(deftest first-witness-termination-in-negation
  (let [boom (volatile! 0)
        facts (core/fact-relation [[1 :a 0] [1 :n 1] [1 :n 2] [1 :n 3]])
        explode (fn [v]
                  (vswap! boom inc)
                  (when (>= v 2) (throw (ex-info "evaluated a later candidate" {:v v})))
                  true)
        q '[:find ?e :where [?e :a _]
            (not-join [?e] [?e :n ?v] [(explode ?v)])]
        [st r] (core/q-with-state ex/unbounded nil q facts {:fns {'explode explode}})]
    (is (not (ex/terminal? st)))
    (is (= #{} (rows-of r)) "the first witness decided the negation")
    (is (= 1 @boom) "a later candidate that would throw was never evaluated")))


(deftest fresh-value-recursion-exhausts-the-cap-instead-of-running-away
  (let [rules '[[(up ?n) [(inc ?n) ?m] (up ?m)]]
        q '[:find ?x :in $ % :where (up 0) [?x :n _]]
        db (core/fact-relation three)]
    (doseq [cap [30 90 300]]
      (is (exhausts? cap q db rules) (str "cap " cap)))))


(def ^:private up-rules '[[(up ?n) [(inc ?n) ?m] (up ?m)]])


(defn- chain
  "A relation of n edges 0 -> 1 -> ... -> n, plus a start marker on 0."
  [n]
  (core/fact-relation (into [[0 :start true]]
                            (map (fn [i] [i :edge (inc i)]) (range n)))))


(deftest stack-safety-fresh-value-recursion-beyond-the-former-overflow
  (let [q '[:find ?x :in $ % :where (up 0) [?x :n _]]
        db (core/fact-relation three)]
    (testing "caps far beyond the ~330-level overflow of the continuation-passing kernel"
      (doseq [cap [1000 5000 60000]]
        (is (exhausts? cap q db up-rules) (str "cap " cap))))
    (testing "the exhaustion is a structured outcome naming the work cap"
      (let [[st r] (run-q {:max-steps 60000} nil q db up-rules)]
        (is (nil? r))
        (is (= {:kind :step-budget-exhausted :steps 60000} (select-keys (ex/outcome st) [:kind :steps])))))))


(deftest stack-safety-mutual-recursion
  (let [rules '[[(ping ?n) [(inc ?n) ?m] (pong ?m)]
                [(pong ?n) [(inc ?n) ?m] (ping ?m)]]
        q '[:find ?x :in $ % :where (ping 0) [?x :n _]]
        db (core/fact-relation three)]
    (doseq [cap [2000 40000]]
      (is (exhausts? cap q db rules) (str "cap " cap)))))


(deftest stack-safety-many-processed-bindings-and-deep-chains
  (testing "a chain deeper than the former overflow completes, unbounded and bounded"
    (let [n 900
          db (chain n)
          q '[:find ?x :in $ % :where (reach 0 ?x)]
          expected (set (map (fn [i] [(inc i)]) (range n)))]
      (is (= expected (rows-of (core/q q db chain-rules))))
      (is (= expected (rows-of (second (run-q {:max-steps 100000000} nil q db chain-rules)))))))
  (testing "many bindings through a disjunction"
    (let [m 5000
          db (core/fact-relation (into [] (mapcat (fn [i] [[i :a i] [i :b i]]) (range m))))
          q '[:find (count ?e) :where [?e :a _] (or [?e :b _] [?e :c _])]]
      (is (= #{[m]} (rows-of (second (run-q {:max-steps 100000000} nil q db))))))))


(deftest stack-safety-nested-evaluation
  (let [n 900
        db (chain n)]
    (testing "a deep recursive rule inside a negation: no witness for an unreachable node"
      (let [q '[:find ?s :in $ % :where [?s :start _]
                (not-join [?s] (reach ?s ?x) [(= ?x 99999)])]]
        (is (= #{[0]} (rows-of (second (run-q {:max-steps 100000000} nil q db chain-rules)))))))
    (testing "a witness found at depth ends the negation"
      (let [q '[:find ?s :in $ % :where [?s :start _]
                (not-join [?s] (reach ?s ?x) [(= ?x 850)])]]
        (is (= #{} (rows-of (second (run-q {:max-steps 100000000} nil q db chain-rules)))))))))


(deftest stack-safety-first-witness-skips-an-infinite-alternative
  (let [rules '[[(w ?e) [?e :n _]]
                [(w ?e) (up 0)]
                [(up ?n) [(inc ?n) ?m] (up ?m)]]
        q '[:find ?e :in $ % :where [?e :n 10] (not-join [?e] (w ?e))]
        db (core/fact-relation [[1 :n 10]])
        [st r] (run-q {:max-steps 2000} nil q db rules)]
    (is (not (ex/terminal? st)) "the first witness stopped the negation before the runaway alternative")
    (is (= #{} (rows-of r)))))


(def ^:private ident-q
  '[:find ?x :in $ ?xs :where [(identity ?xs) [?v ...]] [(ground 1) ?x]])


(deftest function-result-binding-charges-every-attempted-binding
  (let [db (core/fact-relation three)
        steps (fn [n] (steps-of ident-q db (vec (range n))))]
    (testing "ten and ten thousand candidates no longer cost the same"
      (is (< (steps 10) (steps 1000) (steps 10000))))
    (testing "a cap of 100 is exhausted by 10000 candidates, structured, no result"
      (is (exhausts? 100 ident-q db (vec (range 10000))))
      (is (completes? 100 ident-q db (vec (range 10)))))
    (testing "rejected outputs are charged too: nothing survives the filter"
      (let [q '[:find ?x :in $ ?xs :where [(identity ?xs) [?v ...]] [(> ?v 1000000)] [(ground 1) ?x]]]
        (is (exhausts? 200 q db (vec (range 5000))))))
    (testing "relation results"
      (let [q '[:find ?x :in $ ?ts :where [(identity ?ts) [[?a ?b]]] [(ground 1) ?x]]]
        (is (exhausts? 200 q db (mapv (fn [i] [i i]) (range 5000))))
        (is (completes? 200 q db [[1 2]]))))
    (testing "exact boundary on the expansion"
      (let [xs (vec (range 40))
            n (steps-of ident-q db xs)]
        (is (completes? n ident-q db xs))
        (is (exhausts? (dec n) ident-q db xs))))))


(deftest function-result-expansion-honors-first-witness
  (let [explodes (fn []
                   (lazy-seq (cons 1 (lazy-seq (throw (ex-info "expanded past the witness" {}))))))
        facts (core/fact-relation [[1 :a 0]])
        q '[:find ?e :where [?e :a _]
            (not-join [?e] [(explode-seq) [?v ...]])]
        [st r] (core/q-with-state ex/unbounded nil q facts {:fns {'explode-seq explodes}})]
    (is (not (ex/terminal? st)))
    (is (= #{} (rows-of r)) "the first element is a witness; the second is never realized")))


(deftest collection-and-relation-inputs-are-not-enumerated-ahead-of-the-budget
  (let [db (core/fact-relation three)]
    (testing "an infinite collection input ends in structured exhaustion"
      (is (exhausts? 100 '[:find ?x :in $ [?x ...] :where [(ground 1) ?y]]
                     db (iterate inc 0))))
    (testing "an infinite relation input too"
      (is (exhausts? 100 '[:find ?a :in $ [[?a ?b]] :where [(ground 1) ?y]]
                     db (map (fn [i] [i i]) (range)))))))


(deftest checked-source-realization
  (let [rows (mapv (fn [i] [i :a i 1 1]) (range 300))
        view (core/current (core/relation rows))
        q '[:find ?e :where [?e :a 7]]]
    (testing "current-view realization is charged and observed in the :realize phase"
      (let [[st r] (run-q {:max-steps 100} nil q view)]
        (is (nil? r))
        (is (= :realize (:phase (ex/outcome st))))
        (is (= :step-budget-exhausted (:kind (ex/outcome st))))))
    (testing "a large enough allowance completes with the unbounded answer"
      (let [[st r] (run-q {:max-steps 100000} nil q view)]
        (is (not (ex/terminal? st)))
        (is (= (rows-of (core/q q view)) (rows-of r)))))
    (testing "fact-relation index construction is charged"
      (let [[st r] (run-q {:max-steps 10}
                          nil q (core/fact-relation (mapv (fn [i] [i :a i]) (range 300))))]
        (is (nil? r))
        (is (= :realize (:phase (ex/outcome st))))))))


(deftest checked-input-expansion
  (let [facts (core/fact-relation three)
        pairs (mapv (fn [i] [i i]) (range 400))
        q '[:find ?e :in $ [[?e ?x]] :where [?e :n _]]
        [st r] (run-q {:max-steps 100} nil q facts pairs)]
    (is (nil? r))
    (is (= :step-budget-exhausted (:kind (ex/outcome st))))))


(deftest checked-pull-and-aggregation
  (let [db (core/fact-relation (into [] (mapcat (fn [i] [[i :a i] [i :b i] [i :c i]])
                                                (range 60))))
        pull-q '[:find (pull ?e [:a :b :c]) :where [?e :a _]]
        agg-q '[:find (count ?e) :where [?e :a ?v]]]
    (is (exhausts? 200 pull-q db) "pull traversal advances are charged")
    (is (completes? (steps-of pull-q db) pull-q db))
    (is (exhausts? 150 agg-q db) "aggregate inputs are charged")
    (is (completes? (steps-of agg-q db) agg-q db))))


;; ---------------------------------------------------------------------------
;; Deterministic deadline expiry through the integrated kernel
;; ---------------------------------------------------------------------------

(defn- obs-driver
  "A driver whose reading is its own observation count (1, 2, 3, ...); the
   counter is test scaffolding. A deadline D therefore expires at the D-th
   observation."
  []
  (let [n (volatile! 0)]
    {:driver (ex/fn-driver (fn [] (vswap! n inc)))
     :count n}))


(deftest deadline-expiry-in-an-integrated-query-exact-boundary
  (let [facts (core/fact-relation three)
        q '[:find ?e :where [?e :n ?v]]
        {:keys [driver count]} (obs-driver)
        [st0 r0] (run-q {:deadline 1000000} driver q facts)
        m @count]
    (is (not (ex/terminal? st0)))
    (is (some? r0))
    (is (pos? m) "the kernel observes the deadline at its boundaries and per unit")
    (testing "immediately before: the last observation is still live"
      (let [{:keys [driver count]} (obs-driver)
            [st r] (run-q {:deadline (inc m)} driver q facts)]
        (is (not (ex/terminal? st)))
        (is (= (rows-of r0) (rows-of r)))
        (is (= m @count))))
    (testing "exact boundary: expiry at the final observation is a timeout"
      (let [{:keys [driver]} (obs-driver)
            [st r] (run-q {:deadline m} driver q facts)]
        (is (= :timeout (:kind (ex/outcome st))))
        (is (nil? r) "a terminal return exposes no partial result")))
    (testing "immediately after the boundary"
      (let [{:keys [driver count]} (obs-driver)
            [st r] (run-q {:deadline (dec m)} driver q facts)]
        (is (= :timeout (:kind (ex/outcome st))))
        (is (nil? r))
        (is (= (dec m) @count) "no observation is taken after expiry")))))


(deftest every-expiry-point-yields-a-timeout-and-stops-observing
  (let [facts (core/fact-relation three)
        q '[:find ?e :where [?e :n ?v] [(> ?v 15)]]
        {:keys [driver count]} (obs-driver)
        _ (run-q {:deadline 1000000} driver q facts)
        m @count]
    (doseq [d (range 1 (inc m))]
      (let [{:keys [driver count]} (obs-driver)
            [st r] (run-q {:deadline d} driver q facts)]
        (is (= :timeout (:kind (ex/outcome st))) (str "deadline " d))
        (is (nil? r))
        (is (= d @count) "the driver is never read again after the expiring observation")))))


(deftest a-host-function-returning-past-the-deadline-is-not-interpreted
  (let [clock (volatile! 0)
        driver (ex/fn-driver (fn [] @clock))
        calls (volatile! 0)
        slow (fn [_]
               (vswap! calls inc)
               (vreset! clock 101)
               true)
        facts (core/fact-relation three)
        [st r] (core/q-with-state (ex/start {:deadline 100}) driver
                                  '[:find ?e :where [?e :n ?v] [(slow ?v)]]
                                  facts {:fns {'slow slow}})]
    (is (= :timeout (:kind (ex/outcome st))))
    (is (nil? r))
    (is (= 1 @calls) "no further candidate was evaluated after the post-call observation")))


(deftest ownership-is-preserved-on-success-failure-and-timeout
  (let [facts (core/fact-relation three)
        before (pr-str facts)]
    (run-q {:max-steps 100000} nil '[:find ?e :where [?e :n _]] facts)
    (run-q {:max-steps 5} nil '[:find ?e :where [?e :n _]] facts)
    (try (run-q {:max-steps 100000} nil '[:find ?e :where [?e :n _] [(no-such-fn ?e) ?x]] facts)
         (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))
    (is (= before (pr-str facts)) "inputs are neither opened, closed nor changed")))


(deftest a-bounded-state-refuses-an-opened-published-index
  (let [published {:dao.space.query/published true
                   :rows (delay (throw (ex-info "must not be read" {})))}]
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (run-q {:max-steps 100} nil '[:find ?e :where [?e :n _]] published)))
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (run-q {:deadline 100} (ex/fn-driver (constantly 0))
                 '[:find ?e :where [?e :n _]] published)))))
