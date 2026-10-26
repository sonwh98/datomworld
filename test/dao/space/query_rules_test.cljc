(ns dao.space.query-rules-test
  "Phase 3 rules and semantic conformance for dao.space.query
   (docs/design/dao.space.query.datomic-compat.md, supported domain: required-
   bound rule heads and finite positive recursive rules).

   Semantics under test:
   - Positive recursion is evaluated to its LEAST FIXED POINT. A call whose
     key (rule + argument content) is being evaluated further up the path
     reads the tuples tabled so far and records the hit; its leader re-runs
     the pass until a pass finds nothing new. Left, right and mutual
     recursion, cycles, several definitions and a fully unbound entry call
     are complete. The active-call guard of earlier phases was not a
     completeness proof (left recursion lost answers).
   - Required-bound heads `(r [?x] ?y)`: the leading vector names variables
     the call must already have bound; the call is flat `(r 2 ?y)`. An unbound
     required argument is an error naming the variable.
   - Recursion THROUGH negation is refused before any evaluation.
   - The Phase 4 work budget is the termination backstop for fresh-value
     recursion: a bounded call exhausts structurally, never hangs.
   - Kernel contract: [state result] threading, first-witness demand for
     negation, no dynamic vars. Every expectation is derived from an
     independent oracle (a plain breadth-first closure), not from the
     evaluator under test."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]
            [dao.space.query.datomic :as dq]
            [dao.space.query.execution :as exec]))


(def ^:private edges
  "A graph with a cycle (1 -> 2 -> 3 -> 1), a branch (3 -> 4) and a separate
   component (5 -> 6)."
  [[1 2] [2 3] [3 1] [3 4] [5 6]])


(defn- graph [] (query/fact-relation (mapv (fn [[a b]] [a :e b]) edges)))


(defn- qq
  [form & inputs]
  (query/collect (apply query/q form inputs)))


(defn- refusal
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-message e) (str e)))))


(defn- refused-with?
  "True when f throws and its message matches pattern (a boolean helper: see
   dao.space.query-aggregates-test on why no call sits inside an assertion
   form wrapped in `str`)."
  [f pattern]
  (let [m (refusal f)]
    (boolean (and m (re-find pattern m)))))


;; ---------------------------------------------------------------------------
;; The independent oracle
;; ---------------------------------------------------------------------------

(defn- successors [n] (map second (filter #(= n (first %)) edges)))


(defn- closure
  "Set of [a b]: b reachable from a by one or more edges."
  []
  (set (for [a (distinct (map first edges))
             b (loop [seen #{} frontier (successors a)]
                 (let [fresh (remove seen frontier)]
                   (if (empty? fresh)
                     seen
                     (recur (into seen fresh) (mapcat successors fresh)))))]
         [a b])))


(defn- parity-closure
  "Set of [a b]: b reachable from a by a path whose length has `parity`
   (:odd / :even), length >= 1."
  [parity]
  (let [want (if (= parity :odd) 1 0)]
    (set (for [a (distinct (map first edges))
               [b p] (loop [seen #{} frontier (map (fn [n] [n 1]) (successors a))]
                       (let [fresh (remove seen frontier)]
                         (if (empty? fresh)
                           seen
                           (recur (into seen fresh)
                                  (mapcat (fn [[n p]] (map (fn [m] [m (- 1 p)]) (successors n)))
                                          fresh)))))
               :when (= p want)]
           [a b]))))


;; ---------------------------------------------------------------------------
;; Rule sets
;; ---------------------------------------------------------------------------

(def ^:private right-rules
  '[[(path ?a ?b) [?a :e ?b]]
    [(path ?a ?b) [?a :e ?m] (path ?m ?b)]])


(def ^:private left-rules
  '[[(path ?a ?b) [?a :e ?b]]
    [(path ?a ?b) (path ?a ?m) [?m :e ?b]]])


(def ^:private both-rules
  '[[(path ?a ?b) [?a :e ?b]]
    [(path ?a ?b) (path ?a ?m) (path ?m ?b)]])


(def ^:private mutual-rules
  '[[(odd ?a ?b) [?a :e ?b]]
    [(odd ?a ?b) [?a :e ?m] (even ?m ?b)]
    [(even ?a ?b) [?a :e ?m] (odd ?m ?b)]])


;; ---------------------------------------------------------------------------
;; Completeness: left, right, both-sided and mutual recursion, cycles
;; ---------------------------------------------------------------------------

(deftest every-recursion-shape-reaches-the-least-fixed-point
  (let [from-1 (set (map (fn [[_ b]] [b]) (filter #(= 1 (first %)) (closure))))]
    (is (= #{[2] [3] [1] [4]} from-1) "the oracle: the cycle makes 1 reach itself")
    (doseq [[label rules] {"right" right-rules "left" left-rules "both-sided" both-rules}]
      (testing label
        (is (= from-1 (qq '[:find ?x :in $ % :where (path 1 ?x)] (graph) rules)))))))


(deftest an-unbound-entry-call-enumerates-the-whole-relation
  (doseq [[label rules] {"right" right-rules "left" left-rules "both-sided" both-rules}]
    (is (= (closure) (qq '[:find ?a ?b :in $ % :where (path ?a ?b)] (graph) rules)) label)))


(deftest a-bound-second-argument-and-both-bound-calls
  (let [rules left-rules]
    (is (= (set (map (fn [[a _]] [a]) (filter #(= 4 (second %)) (closure))))
           (qq '[:find ?a :in $ % :where (path ?a 4)] (graph) rules)))
    (is (= #{[true]} (qq '[:find ?ok :in $ % :where (path 1 4) [(ground true) ?ok]]
                         (graph) rules)))
    (is (= #{} (qq '[:find ?ok :in $ % :where (path 4 1) [(ground true) ?ok]]
                   (graph) rules)))))


(deftest mutual-recursion
  (doseq [[name parity] [['odd :odd] ['even :even]]]
    (is (= (parity-closure parity)
           (qq [:find '?a '?b :in '$ '% :where (list name '?a '?b)] (graph) mutual-rules))
        (str name))))


(deftest multiple-definitions-union-their-answers
  (let [rules '[[(near ?a ?b) [?a :e ?b]]
                [(near ?a ?b) [?b :e ?a]]]]
    (is (= (set (concat (map (fn [[a b]] [a b]) edges) (map (fn [[a b]] [b a]) edges)))
           (qq '[:find ?a ?b :in $ % :where (near ?a ?b)] (graph) rules)))))


(deftest a-rule-used-twice-in-one-query-is-independent-per-call
  (let [q '[:find ?x ?y :in $ % :where (path 1 ?x) (path 5 ?y)]
        expect (set (for [[x] #{[2] [3] [1] [4]} [y] #{[6]}] [x y]))]
    (is (= expect (qq q (graph) left-rules)))))


(deftest recursion-through-the-clauses-of-a-larger-body
  (let [rules '[[(reaches-leaf ?a ?l) [?a :e ?l] (not-join [?l] [?l :e _])]
                [(reaches-leaf ?a ?l) [?a :e ?m] (reaches-leaf ?m ?l)]]]
    ;; leaves (no out-edge): 4 and 6
    (is (= #{[1 4] [2 4] [3 4] [5 6]}
           (qq '[:find ?a ?l :in $ % :where (reaches-leaf ?a ?l)] (graph) rules)))))


;; ---------------------------------------------------------------------------
;; Required-bound heads
;; ---------------------------------------------------------------------------

(def ^:private friend-facts
  [[1 :f 2] [3 :f 2] [4 :f 5] [1 :g 9] [3 :g 8]])


(defn- friends [] (query/fact-relation friend-facts))


(deftest required-bound-heads-take-their-arguments-from-the-call
  (let [rules '[[(fan [?x] ?e) [?e :f ?x]]]]
    (testing "a constant argument"
      (is (= #{[1] [3]} (qq '[:find ?e :in $ % :where (fan 2 ?e)] (friends) rules))))
    (testing "an :in-bound argument"
      (is (= #{[4]} (qq '[:find ?e :in $ % ?x :where (fan ?x ?e)] (friends) rules 5))))
    (testing "an argument bound by an earlier clause"
      (is (= #{[2 1] [2 3]}
             (qq '[:find ?x ?e :in $ % :where [1 :f ?x] (fan ?x ?e)] (friends) rules))))
    (testing "several required variables share the one leading vector"
      (let [rules2 '[[(both [?x ?y] ?e) [?e :f ?x] [?e :g ?y]]]]
        (is (= #{[1]} (qq '[:find ?e :in $ % :where (both 2 9 ?e)] (friends) rules2)))
        (is (= #{} (qq '[:find ?e :in $ % :where (both 2 7 ?e)] (friends) rules2)))))
    (testing "the find variable bound only by the rule call is not mistaken for unbound"
      (is (= #{[1] [3]} (qq '[:find ?e :in $ % :where (fan 2 ?e)] (friends) rules))))))


(deftest an-unbound-required-argument-is-an-error-naming-the-variable
  (let [rules '[[(fan [?x] ?e) [?e :f ?x]]]]
    (is (refused-with? (fn [] (qq '[:find ?e :in $ % :where (fan ?z ?e)] (friends) rules))
                       #"required-bound variable is not bound"))
    (is (refused-with? (fn [] (qq '[:find ?e :in $ % :where (fan _ ?e)] (friends) rules))
                        #"required-bound variable is not bound"))
    (is (refused-with? (fn [] (qq '[:find ?e :in $ % :where (fan ?z ?e) [?e :g ?z]] (friends) rules))
                        #"required-bound variable is not bound")
        "a later clause does not rescue it: the call is evaluated with what is bound at the call")))


(deftest required-bound-heads-compose-with-recursion
  (let [rules '[[(down [?a] ?b) [?a :e ?b]]
                [(down [?a] ?b) [?a :e ?m] (down ?m ?b)]]]
    (is (= #{[2] [3] [1] [4]} (qq '[:find ?b :in $ % :where (down 1 ?b)] (graph) rules)))))


(deftest rule-call-arguments-are-flat
  (let [rules '[[(fan [?x] ?e) [?e :f ?x]]]]
    (is (refused-with? (fn [] (qq '[:find ?e :in $ % :where (fan [2] ?e)] (friends) rules))
                       #"flat"))
    (testing "and the vector's variables are not silently counted as unbound-find errors"
      (is (refused-with? (fn [] (qq '[:find ?x :in $ % :where (fan [?x] ?e)] (friends) rules))
                          #"flat")))))


(deftest malformed-heads-are-refused
  (is (refused-with? (fn [] (qq '[:find ?e :in $ % :where (r 2 ?e)] (friends)
                          '[[(r ?x [?y]) [?y :f ?x]]]))
                      #"malformed rule head")))


;; ---------------------------------------------------------------------------
;; Recursion through negation
;; ---------------------------------------------------------------------------

(deftest recursion-through-negation-is-refused-before-evaluation
  (let [called (volatile! false)
        fns {'boom (fn [_] (vreset! called true) true)}]
    (doseq [[label rules]
            {"self" '[[(p ?x) [?x :e _] (not (p ?x))]]
             "mutual" '[[(a ?x) [?x :e _] (not (b ?x))]
                        [(b ?x) [?x :e _] [(boom ?x)] (a ?x)]]
             "not-join" '[[(p ?x) [?x :e _] (not-join [?x] (p ?x))]]
             "through a longer chain" '[[(a ?x) [?x :e _] (not (b ?x))]
                                        [(b ?x) (c ?x)]
                                        [(c ?x) [?x :e _] (a ?x)]]}]
      (is (refused-with? (fn [] (query/collect
                            (query/q '[:find ?x :in $ % :where (p ?x) (a ?x)]
                                     (graph) rules {:fns fns})))
                         #"recursion through negation")
          label)
      (is (false? @called) (str label ": nothing was evaluated")))))


(deftest negation-outside-the-cycle-is-fine
  (testing "a stratified rule set: a negates b, b is recursive but never reaches a"
    (let [rules '[[(path ?a ?b) [?a :e ?b]]
                  [(path ?a ?b) [?a :e ?m] (path ?m ?b)]
                  [(cannot-reach-4 ?x) [?x :e _] (not-join [?x] (path ?x 4))]]]
      (is (= #{[5]} (qq '[:find ?x :in $ % :where (cannot-reach-4 ?x)] (graph) rules)))))
  (testing "a query-level negation of a recursive rule"
    (is (= #{[5]} (qq '[:find ?x :in $ % :where [?x :e _] (not-join [?x] (path ?x 4))]
                      (graph) right-rules)))))


;; ---------------------------------------------------------------------------
;; The work budget is the termination backstop
;; ---------------------------------------------------------------------------

(def ^:private up-rules '[[(up ?n) [(inc ?n) ?m] (up ?m)]])


(defn- steps-run
  [opts query & inputs]
  (apply query/q-with-state (exec/start opts) nil query inputs))


(deftest a-divergent-rule-exhausts-the-budget-instead-of-hanging
  (doseq [cap [50 500 5000]]
    (let [[st r] (steps-run {:max-steps cap} '[:find ?x :in $ % :where (up 0) [?x :e _]]
                            (graph) up-rules)]
      (is (= :step-budget-exhausted (:kind (exec/outcome st))) (str cap))
      (is (nil? r) "no partial result"))))


(deftest the-adapter-bounds-a-divergent-rule-structurally
  (let [d (try (dq/q {:query '[:find ?x :in $ % :where (up 0) [?x :e _]]
                      :args [(graph) up-rules]
                      ::dq/max-steps 2000})
               nil
               (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e)))]
    (is (= :step-budget-exhausted (::dq/error d)))
    (is (= :cognitect.anomalies/interrupted (:cognitect.anomalies/category d)))))


(deftest a-recursive-rule-completes-under-an-exact-allowance
  (let [q '[:find ?x :in $ % :where (path 1 ?x)]
        [st0 r0] (steps-run {} q (graph) left-rules)
        n (exec/steps st0)]
    (is (some? r0))
    (is (= #{[2] [3] [1] [4]} (query/collect r0)))
    (let [[st r] (steps-run {:max-steps n} q (graph) left-rules)]
      (is (not (exec/terminal? st)) "the exact allowance completes")
      (is (= (query/collect r0) (query/collect r))))
    (let [[st r] (steps-run {:max-steps (dec n)} q (graph) left-rules)]
      (is (= :step-budget-exhausted (:kind (exec/outcome st))) "one unit short exhausts")
      (is (nil? r)))))


(deftest non-recursive-rules-cost-exactly-one-pass
  (let [q '[:find ?a ?b :in $ % :where (near ?a ?b)]
        rules '[[(near ?a ?b) [?a :e ?b]]]
        [st _] (steps-run {} q (graph) rules)
        [st2 _] (steps-run {} '[:find ?a ?b :where [?a :e ?b]] (graph))]
    (is (< (exec/steps st) (+ (exec/steps st2) 40))
        "no second pass: a rule that never re-enters itself is evaluated once")))


;; ---------------------------------------------------------------------------
;; First-witness demand survives the fixed point
;; ---------------------------------------------------------------------------

(deftest negation-stops-at-the-first-witness-of-a-recursive-rule
  (let [ticks (volatile! 0)
        rules '[[(w ?x ?y) [?x :e ?y] [(tick ?y)]]
                [(w ?x ?y) [?x :e ?m] (w ?m ?y)]]
        q '[:find ?x :in $ % :where [(ground 3) ?x] (not-join [?x] (w ?x ?y))]
        r (query/collect (query/q q (graph) rules {:fns {'tick (fn [_] (vswap! ticks inc) true)}}))]
    (is (= #{} r) "3 has successors, so the negation fails")
    (is (= 1 @ticks) "the first witness stopped the evaluation before the second successor")))


;; ---------------------------------------------------------------------------
;; The adapter
;; ---------------------------------------------------------------------------

(defn- adapter-error
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


(deftest the-adapter-evaluates-recursive-rules-completely
  (is (= (qq '[:find ?x :in $ % :where (path 1 ?x)] (graph) left-rules)
         (set (dq/q '[:find ?x :in $ % :where (path 1 ?x)] (graph) left-rules))))
  (is (= (set (map vec (closure)))
         (set (dq/q {:query '[:find ?a ?b :in $ % :where (path ?a ?b)]
                     :args [(graph) both-rules]
                     ::dq/max-steps 1000000})))))


(deftest the-adapter-classifies-rule-refusals
  (let [d (adapter-error (fn [] (dq/q '[:find ?x :in $ % :where (p ?x)]
                                (graph) '[[(p ?x) [?x :e _] (not (p ?x))]])))]
    (is (= :unsupported (::dq/error d)))
    (is (= :recursion-through-negation (:reason d))))
  (let [d (adapter-error (fn [] (dq/q '[:find ?e :in $ % :where (fan ?z ?e)]
                                (friends) '[[(fan [?x] ?e) [?e :f ?x]]])))]
    (is (= :invalid-request (::dq/error d))))
  (let [d (adapter-error (fn [] (dq/q '[:find ?e :in $ % :where (fan [2] ?e)]
                                (friends) '[[(fan [?x] ?e) [?e :f ?x]]])))]
    (is (= :invalid-request (::dq/error d)))))
