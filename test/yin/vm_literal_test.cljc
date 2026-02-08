(ns yin.vm-literal-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]))


(deftest test-integer-literal
  (testing "Integer literals evaluate to themselves"
    (let [ast {:type :literal, :value 42}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 42 (:value result)) "42 should evaluate to 42")
      (is (nil? (:control result)) "Control should be nil after evaluation")
      (is (nil? (:continuation result))
          "Continuation should be nil after evaluation"))))


(deftest test-string-literal
  (testing "String literals evaluate to themselves"
    (let [ast {:type :literal, :value "hello world"}
          result (walker/run (walker/make-state {}) ast)]
      (is (= "hello world" (:value result))
          "String should evaluate to itself"))))


(deftest test-boolean-literals
  (testing "Boolean literals evaluate to themselves"
    (let [true-ast {:type :literal, :value true}
          false-ast {:type :literal, :value false}
          true-result (walker/run (walker/make-state {}) true-ast)
          false-result (walker/run (walker/make-state {}) false-ast)]
      (is (true? (:value true-result)) "true should evaluate to true")
      (is (false? (:value false-result)) "false should evaluate to false"))))


(deftest test-nil-literal
  (testing "Nil literal evaluates to nil"
    (let [ast {:type :literal, :value nil}
          result (walker/run (walker/make-state {}) ast)]
      (is (nil? (:value result)) "nil should evaluate to nil"))))


(deftest test-float-literal
  (testing "Floating point literals evaluate to themselves"
    (let [ast {:type :literal, :value 3.14159}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 3.14159 (:value result)) "Float should evaluate to itself"))))


(deftest test-negative-number-literal
  (testing "Negative numbers evaluate to themselves"
    (let [ast {:type :literal, :value -99}
          result (walker/run (walker/make-state {}) ast)]
      (is (= -99 (:value result))
          "Negative number should evaluate to itself"))))


(deftest test-vector-literal
  (testing "Vector literals evaluate to themselves"
    (let [ast {:type :literal, :value [1 2 3]}
          result (walker/run (walker/make-state {}) ast)]
      (is (= [1 2 3] (:value result)) "Vector should evaluate to itself"))))


(deftest test-map-literal
  (testing "Map literals evaluate to themselves"
    (let [ast {:type :literal, :value {:x 10, :y 20}}
          result (walker/run (walker/make-state {}) ast)]
      (is (= {:x 10, :y 20} (:value result)) "Map should evaluate to itself"))))


(deftest test-keyword-literal
  (testing "Keyword literals evaluate to themselves"
    (let [ast {:type :literal, :value :status}
          result (walker/run (walker/make-state {}) ast)]
      (is (= :status (:value result)) "Keyword should evaluate to itself"))))


(deftest test-literal-evaluation-is-single-step
  (testing "Literal evaluation completes in one step"
    (let [ast {:type :literal, :value 42}
          initial-state (assoc (walker/make-state {}) :control ast)
          after-one-step (walker/eval initial-state nil)]
      (is (= 42 (:value after-one-step)) "Value should be set after one step")
      (is (nil? (:control after-one-step))
          "Control should be nil after one step")
      (is (nil? (:continuation after-one-step))
          "Continuation should be nil after one step"))))


(deftest test-various-literal-types
  (testing "All Clojure value types work as literals"
    (doseq [[desc value] [["integer" 42] ["string" "test"] ["boolean" true]
                          ["float" 3.14] ["keyword" :key] ["vector" [1 2]]
                          ["map" {:a 1}] ["set" #{1 2}]]]
      (testing desc
        (let [ast {:type :literal, :value value}
              result (walker/run (walker/make-state {}) ast)]
          (is (= value (:value result))
              (str desc " should evaluate to itself")))))))
