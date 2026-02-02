(ns yin.vm-basic-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]))


(deftest test-literal-evaluation
  (testing "Literal values evaluate to themselves"
    (let [ast {:type :literal, :value 42}
          result (vm/run (vm/make-state {}) ast)]
      (is (= 42 (:value result)) "Integer literal should evaluate to itself"))
    (let [ast {:type :literal, :value "hello"}
          result (vm/run (vm/make-state {}) ast)]
      (is (= "hello" (:value result))
          "String literal should evaluate to itself"))))


(deftest test-variable-lookup
  (testing "Variable lookup in environment"
    (let [ast {:type :variable, :name 'x}
          result (vm/run (vm/make-state {'x 100}) ast)]
      (is (= 100 (:value result)) "Should lookup variable x in environment"))))


(deftest test-lambda-closure
  (testing "Lambda creates a closure"
    (let [ast {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
          result (vm/run (vm/make-state {}) ast)]
      (is (= :closure (:type (:value result))) "Lambda should create a closure")
      (is (= ['x] (:params (:value result)))
          "Closure should have correct parameters"))))


(deftest test-primitive-addition
  (testing "Direct primitive addition"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :literal, :value add-fn},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/run (vm/make-state {}) ast)]
      (is (= 30 (:value result)) "10 + 20 should equal 30"))))


(deftest test-lambda-addition
  (testing "Lambda that adds two numbers"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x y) (+ x y)) 3 5)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x 'y],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :variable, :name 'y}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 5}]}
          result (vm/run (vm/make-state {'+ add-fn}) ast)]
      (is (= 8 (:value result)) "Lambda addition of 3 and 5 should equal 8"))))


(deftest test-increment-continuation
  (testing "Simple continuation test - increment by 1"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x) (+ x 1)) 5)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          result (vm/run (vm/make-state {'+ add-fn}) ast)]
      (is (= 6 (:value result)) "Should increment 5 to 6"))))


(deftest test-all-arithmetic-primitives
  (testing "All arithmetic primitive operations"
    (let [state (vm/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (is (= 30 (:value (vm/run state (literal-ast '+ 10 20))))
          "Addition: 10 + 20 = 30")
      (is (= 5 (:value (vm/run state (literal-ast '- 15 10))))
          "Subtraction: 15 - 10 = 5")
      (is (= 50 (:value (vm/run state (literal-ast '* 5 10))))
          "Multiplication: 5 * 10 = 50")
      (is (= 4 (:value (vm/run state (literal-ast '/ 20 5))))
          "Division: 20 / 5 = 4"))))


(deftest test-comparison-operations
  (testing "Comparison primitive operations"
    (let [state (vm/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (is (true? (:value (vm/run state (literal-ast '= 5 5))))
          "Equality: 5 = 5")
      (is (false? (:value (vm/run state (literal-ast '= 5 6))))
          "Inequality: 5 ≠ 6")
      (is (true? (:value (vm/run state (literal-ast '< 3 5))))
          "Less than: 3 < 5")
      (is (true? (:value (vm/run state (literal-ast '> 10 5))))
          "Greater than: 10 > 5"))))


(comment)
