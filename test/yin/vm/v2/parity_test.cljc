(ns yin.vm.v2.parity-test
  "One macro-free corpus, run through the v1 and v2 ast-walkers in the same
   process, compared as normalized values.

   Co-loading both is deliberate and costs nothing: the end condition governs
   namespaces under `yin.vm.v2` and `dao.*.v2`, not test namespaces, and the
   repo already co-loads five VMs in `yin.vm.parity-test`.

   The corpus is macro-free because neither evaluator has a `macro-expand`
   branch. Where v2 diverges on purpose, the case lives in
   `yin.vm.v2.ast-walker-test` and in the divergence register, not here."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]
            [yin.vm :as v1]
            [yin.vm.ast-walker :as v1-ast-walker]
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
  "Programs both evaluators must agree on. Each is [name ast]."
  [["literal int" (lit 42)]
   ["literal string" (lit "hello world")]
   ["literal nil" (lit nil)]
   ["literal vector" (lit [1 2 3])]
   ["literal map" (lit {:x 10, :y 20})]
   ["literal set" (lit #{1 2})]
   ["addition" (binop '+ 10 20)]
   ["subtraction" (binop '- 15 10)]
   ["multiplication" (binop '* 5 10)]
   ["division" (binop '/ 20 5)]
   ["equality true" (binop '= 5 5)]
   ["equality false" (binop '= 5 6)]
   ["less than" (binop '< 3 5)]
   ["greater than" (binop '> 10 5)]
   ["nested application"
    {:type :application,
     :operator {:type :variable, :name '+},
     :operands [(lit 1) (binop '+ 2 3)]}]
   ["if true branch"
    {:type :if, :test (binop '< 1 2), :consequent (lit 100), :alternate (lit 200)}]
   ["if false branch"
    {:type :if, :test (binop '< 5 2), :consequent (lit 100), :alternate (lit 200)}]
   ["lambda application"
    {:type :application,
     :operator {:type :lambda,
                :params ['x],
                :body {:type :application,
                       :operator {:type :variable, :name '+},
                       :operands [{:type :variable, :name 'x} (lit 1)]}},
     :operands [(lit 10)]}]
   ["closure value"
    {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}]
   ["arity-0 lambda"
    {:type :application,
     :operator {:type :lambda, :params [], :body (lit 7)},
     :operands []}]
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
                        :operands [{:type :variable, :name 'n} (lit 3)]}}]}]
   ["collection primitives"
    {:type :application,
     :operator {:type :variable, :name 'first},
     :operands [(lit [9 8 7])]}]
   ["conj onto rest"
    {:type :application,
     :operator {:type :variable, :name 'conj},
     :operands [{:type :application,
                 :operator {:type :variable, :name 'rest},
                 :operands [(lit [1 2 3])]}
                (lit 4)]}]
   ["store put then get"
    {:type :vm/store-put, :key :parity/k, :val 11}]
   ["gensym" {:type :vm/gensym, :prefix "p"}]
   ["stream make" {:type :stream/make, :buffer 8}]])


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

(defn- run-v1
  [ast]
  (let [in-stream (ds/open! {:dao.stream/type :ringbuffer, :capacity nil})
        vm (assoc (v1-ast-walker/create-vm)
                  :in-stream in-stream
                  :in-cursor {:position 0}
                  :halted? false)]
    (ds/append! in-stream (vec (v1/ast->datoms ast)))
    (normalize (v1/value (v1/run vm)))))


(defn- run-v2
  [ast]
  (normalize (tu/compile-and-run ast)))


(deftest ast-walker-parity-test
  (doseq [[name ast] corpus]
    (testing name
      (let [expected (run-v1 ast)]
        (is (= expected (run-v2 ast)))))))


(deftest ffi-round-trip-parity-test
  (testing "A host call returns the same value on both evaluators"
    (let [ast {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 42)]}
          v1-result (v1/value (v1/eval (v1-ast-walker/create-vm
                                         {:bridge {:op/echo identity}})
                                       ast))
          v2-result (v2/value (v2/eval (tu/create-vm {:bridge {:op/echo
                                                               identity}})
                                       ast))]
      (is (= 42 v1-result))
      (is (= v1-result v2-result)))))


(deftest blocked-read-parity-test
  (testing "Reading an empty stream blocks on both evaluators"
    (let [ast (fn [stream-ref]
                {:type :application,
                 :operator {:type :lambda,
                            :params ['c],
                            :body {:type :stream/next,
                                   :source {:type :variable, :name 'c}}},
                 :operands [{:type :stream/cursor,
                             :source (lit stream-ref)}]})
          v1-vm (v1/eval (v1-ast-walker/create-vm) {:type :stream/make,
                                                    :buffer 4})
          v2-vm (v2/eval (tu/create-vm) {:type :stream/make, :buffer 4})]
      (is (= :yin/blocked (v1/value (v1/eval v1-vm (ast (v1/value v1-vm))))))
      (is (= :yin/blocked (v2/value (v2/eval v2-vm (ast (v2/value v2-vm)))))))))


(deftest stream-round-trip-parity-test
  (testing "put then cursor+next yields the same value on both evaluators"
    (let [read-ast (fn [stream-ref]
                     {:type :application,
                      :operator {:type :lambda,
                                 :params ['c],
                                 :body {:type :stream/next,
                                        :source {:type :variable, :name 'c}}},
                      :operands [{:type :stream/cursor,
                                  :source (lit stream-ref)}]})
          v1-result (let [vm0 (v1/eval (v1-ast-walker/create-vm)
                                       {:type :stream/make, :buffer 4})
                          sref (v1/value vm0)
                          vm1 (v1/eval vm0
                                       {:type :stream/put,
                                        :target (lit sref),
                                        :val (lit 99)})]
                      [(v1/value vm1) (v1/value (v1/eval vm1 (read-ast sref)))])
          v2-result (let [vm0 (v2/eval (tu/create-vm) {:type :stream/make,
                                                       :buffer 4})
                          sref (v2/value vm0)
                          vm1 (v2/eval vm0
                                       {:type :stream/put,
                                        :target (lit sref),
                                        :val (lit 99)})]
                      [(v2/value vm1) (v2/value (v2/eval vm1 (read-ast sref)))])]
      (is (= [99 99] v1-result))
      (is (= v1-result v2-result)))))
