(ns yin.vm.v2.parity-test
  "One macro-free corpus, run through the v2 ast-walker and compared against
   pinned expected values.

   The corpus was originally compared live against v1 (`yin.vm` /
   `yin.vm.ast-walker`) in the same process. Before v1's deletion
   (docs/design/yin.vm.v1-retirement.implementation-plan.md, D4), each row's
   `expected` column was captured by running v1 once — the literal value v1
   returned, recorded in the source, not a formula — on 2026-09-16, against
   the tree that still had v1. The FFI, blocked-read and stream round-trip
   cases below pin their normalized shapes the same way.

   The corpus is macro-free because neither evaluator has a `macro-expand`
   branch. Where v2 diverges on purpose, the case lives in
   `yin.vm.v2.ast-walker-test` and in the divergence register, not here."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.v2 :as v2]
            [yin.vm.v2.test-utils :as tu]))


;; =============================================================================
;; The corpus
;; =============================================================================

(defn- binop
  [op a b]
  {:type :application,
   :operator {:type :variable, :name op},
   :operands [{:type :literal, :value a} {:type :literal, :value b}]})


(defn- lit
  [v]
  {:type :literal, :value v})


(def corpus
  "Programs with the value v1 returned for each, captured 2026-09-16 (D4).
   Each is [name ast expected]."
  [["literal int" (lit 42) 42]
   ["literal string" (lit "hello world") "hello world"]
   ["literal nil" (lit nil) nil]
   ["literal vector" (lit [1 2 3]) [1 2 3]]
   ["literal map" (lit {:x 10, :y 20}) {:x 10, :y 20}]
   ["literal set" (lit #{1 2}) #{1 2}]
   ["addition" (binop '+ 10 20) 30]
   ["subtraction" (binop '- 15 10) 5]
   ["multiplication" (binop '* 5 10) 50]
   ["division" (binop '/ 20 5) 4]
   ["equality true" (binop '= 5 5) true]
   ["equality false" (binop '= 5 6) false]
   ["less than" (binop '< 3 5) true]
   ["greater than" (binop '> 10 5) true]
   ["nested application"
    {:type :application,
     :operator {:type :variable, :name '+},
     :operands [(lit 1) (binop '+ 2 3)]}
    6]
   ["if true branch"
    {:type :if, :test (binop '< 1 2), :consequent (lit 100), :alternate (lit 200)}
    100]
   ["if false branch"
    {:type :if, :test (binop '< 5 2), :consequent (lit 100), :alternate (lit 200)}
    200]
   ["lambda application"
    {:type :application,
     :operator {:type :lambda,
                :params ['x],
                :body {:type :application,
                       :operator {:type :variable, :name '+},
                       :operands [{:type :variable, :name 'x} (lit 1)]}},
     :operands [(lit 10)]}
    11]
   ["closure value"
    {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
    {:type :closure, :params ['x], :body {:type :variable, :name 'x}}]
   ["arity-0 lambda"
    {:type :application,
     :operator {:type :lambda, :params [], :body (lit 7)},
     :operands []}
    7]
   ["higher order"
    {:type :application,
     :operator {:type :lambda,
                :params ['f],
                :body {:type :application,
                       :operator {:type :variable, :name 'f},
                       :operands [(lit 5)]}},
     :operands [{:type :lambda,
                 :params ['n],
                 :body {:type :application,
                        :operator {:type :variable, :name '*},
                        :operands [{:type :variable, :name 'n} (lit 3)]}}]}
    15]
   ["collection primitives"
    {:type :application,
     :operator {:type :variable, :name 'first},
     :operands [(lit [9 8 7])]}
    9]
   ["conj onto rest"
    {:type :application,
     :operator {:type :variable, :name 'conj},
     :operands [{:type :application,
                 :operator {:type :variable, :name 'rest},
                 :operands [(lit [1 2 3])]}
                (lit 4)]}
    [2 3 4]]
   ["store put then get"
    {:type :vm/store-put, :key :parity/k, :val 11}
    11]
   ["gensym" {:type :vm/gensym, :prefix "p"} :p-0]
   ["stream make" {:type :stream/make, :buffer 8}
    {:type :stream-ref, :id :stream-0}]])


;; =============================================================================
;; Normalization
;; =============================================================================

(defn normalize
  "Strip what is legitimately VM-internal before comparison.

   A closure carries its defining environment, which holds host functions; a
   stream reference carries a store key. Both are compared by shape."
  [value]
  (cond (and (map? value) (= :closure (:type value)))
        {:type :closure, :params (:params value), :body (:body value)}
        (and (map? value) (= :stream-ref (:type value)))
        {:type :stream-ref, :id (:id value)}
        (fn? value) :host-fn
        :else value))


;; =============================================================================
;; Runners
;; =============================================================================

(defn- run-v2
  [ast]
  (normalize (tu/compile-and-run ast)))


(deftest ast-walker-parity-test
  (doseq [[name ast expected] corpus]
    (testing name
      (is (= expected (run-v2 ast))))))


(deftest ffi-round-trip-parity-test
  (testing "A host call returns the value v1 returned (42, captured 2026-09-16)"
    (let [ast {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 42)]}
          seen (atom [])
          v2-result (v2/value (v2/eval (tu/create-vm
                                         {:bridge {:op/echo (fn [x]
                                                              (swap! seen conj x)
                                                              x)}})
                                       ast))]
      (is (= [42] @seen) "the operand crossed the bridge")
      (is (= 42 v2-result)))))


(deftest blocked-read-parity-test
  (testing "Reading an empty stream blocks (v1 returned :yin/blocked,
            captured 2026-09-16)"
    (let [ast (fn [stream-ref]
                {:type :application,
                 :operator {:type :lambda,
                            :params ['c],
                            :body {:type :stream/next,
                                   :source {:type :variable, :name 'c}}},
                 :operands [{:type :stream/cursor,
                             :source (lit stream-ref)}]})
          v2-vm (v2/eval (tu/create-vm) {:type :stream/make, :buffer 4})
          blocked (v2/eval v2-vm (ast (v2/value v2-vm)))]
      (is (v2/blocked? blocked))
      (is (= :yin/blocked (v2/value blocked))))))


(deftest stream-round-trip-parity-test
  (testing "put then cursor+next yields the values v1 yielded ([99 99],
            captured 2026-09-16)"
    (let [read-ast (fn [stream-ref]
                     {:type :application,
                      :operator {:type :lambda,
                                 :params ['c],
                                 :body {:type :stream/next,
                                        :source {:type :variable, :name 'c}}},
                      :operands [{:type :stream/cursor,
                                  :source (lit stream-ref)}]})
          v2-result (let [vm0 (v2/eval (tu/create-vm) {:type :stream/make,
                                                       :buffer 4})
                          sref (v2/value vm0)
                          vm1 (v2/eval vm0
                                       {:type :stream/put,
                                        :target (lit sref),
                                        :val (lit 99)})]
                      [(v2/value vm1) (v2/value (v2/eval vm1 (read-ast sref)))])]
      (is (= 99 (first v2-result)) "put returns the value")
      (is (= [99 99] v2-result)))))
