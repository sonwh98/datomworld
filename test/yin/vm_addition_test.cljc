(ns yin.vm-addition-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]))


(deftest test-primitive-addition
  (testing "Direct primitive addition"
    (let [add-fn (get vm/primitives '+)
          ;; AST: (+ 10 20)
          ast {:type :application,
               :operator {:type :literal, :value add-fn},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 30 (:value result)) "10 + 20 should equal 30"))))


(deftest test-lambda-addition-two-args
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
          result (walker/run (walker/make-state {'+ add-fn}) ast)]
      (is (= 8 (:value result)) "Lambda addition of 3 and 5 should equal 8"))))


(deftest test-various-primitive-additions
  (testing "Various addition cases"
    (let [add-fn (get vm/primitives '+)
          add-ast (fn [a b]
                    {:type :application,
                     :operator {:type :literal, :value add-fn},
                     :operands [{:type :literal, :value a}
                                {:type :literal, :value b}]})]
      (is (= 0 (:value (walker/run (walker/make-state {}) (add-ast 0 0))))
          "0 + 0 = 0")
      (is (= 5 (:value (walker/run (walker/make-state {}) (add-ast 2 3))))
          "2 + 3 = 5")
      (is (= 100 (:value (walker/run (walker/make-state {}) (add-ast 50 50))))
          "50 + 50 = 100")
      (is (= 0 (:value (walker/run (walker/make-state {}) (add-ast -5 5))))
          "-5 + 5 = 0")
      (is (= -10 (:value (walker/run (walker/make-state {}) (add-ast -3 -7))))
          "-3 + -7 = -10"))))


(deftest test-nested-lambda-addition
  (testing "Nested lambda addition expressions"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x) ((lambda (y) (+ x y)) 5)) 3)
          ;; This creates a closure that captures x=3, then applies it to
          ;; y=5
          ast {:type :application,
               :operator
                 {:type :lambda,
                  :params ['x],
                  :body {:type :application,
                         :operator
                           {:type :lambda,
                            :params ['y],
                            :body {:type :application,
                                   :operator {:type :variable, :name '+},
                                   :operands [{:type :variable, :name 'x}
                                              {:type :variable, :name 'y}]}},
                         :operands [{:type :literal, :value 5}]}},
               :operands [{:type :literal, :value 3}]}
          result (walker/run (walker/make-state {'+ add-fn}) ast)]
      (is (= 8 (:value result))
          "Nested lambda should correctly capture and use environment"))))


(deftest test-lambda-with-multiple-operations
  (testing "Lambda with multiple primitive operations"
    (let [add-fn (get vm/primitives '+)
          sub-fn (get vm/primitives '-)
          ;; AST: ((lambda (a b) (+ a (- b 1))) 10 5)
          ;; Should compute: 10 + (5 - 1) = 10 + 4 = 14
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['a 'b],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands
                                   [{:type :variable, :name 'a}
                                    {:type :application,
                                     :operator {:type :variable, :name '-},
                                     :operands [{:type :variable, :name 'b}
                                                {:type :literal, :value 1}]}]}},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 5}]}
          result (walker/run (walker/make-state {'+ add-fn, '- sub-fn}) ast)]
      (is (= 14 (:value result)) "Should compute 10 + (5 - 1) = 14"))))


(deftest test-all-arithmetic-primitives
  (testing "All arithmetic primitive operations"
    (let [state (walker/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (testing "Addition"
        (is (= 30 (:value (walker/run state (literal-ast '+ 10 20))))))
      (testing "Subtraction"
        (is (= 5 (:value (walker/run state (literal-ast '- 15 10))))))
      (testing "Multiplication"
        (is (= 50 (:value (walker/run state (literal-ast '* 5 10))))))
      (testing "Division"
        (is (= 4 (:value (walker/run state (literal-ast '/ 20 5)))))))))


(deftest test-comparison-operations
  (testing "Comparison primitive operations"
    (let [state (walker/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (testing "Equality"
        (is (true? (:value (walker/run state (literal-ast '= 5 5)))))
        (is (false? (:value (walker/run state (literal-ast '= 5 6))))))
      (testing "Less than"
        (is (true? (:value (walker/run state (literal-ast '< 3 5)))))
        (is (false? (:value (walker/run state (literal-ast '< 5 3))))))
      (testing "Greater than"
        (is (true? (:value (walker/run state (literal-ast '> 10 5)))))
        (is (false? (:value (walker/run state (literal-ast '> 3 7)))))))))
