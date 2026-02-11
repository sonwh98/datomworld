(ns yin.vm.ast-walker-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]))


;; =============================================================================
;; AST Walker VM tests (direct AST interpretation via protocols)
;; =============================================================================

(defn compile-and-run
  [ast]
  (-> (ast-walker/create-vm)
      (vm/load-program ast)
      (vm/run)
      (vm/value)))


(deftest literal-test
  (testing "Literal via AST walker"
    (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest arithmetic-test
  (testing "Addition (+ 10 20) via AST walker"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest conditional-test
  (testing "If-else (true case) via AST walker"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case) via AST walker"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest lambda-test
  (testing "Lambda application ((fn [x] (+ x 1)) 10) via AST walker"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run ast))))))


(deftest nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3)) via AST walker"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))


(deftest test-variable-lookup
  (testing "Variable lookup in environment"
    (let [ast {:type :variable, :name 'x}
          result (ast-walker/run (ast-walker/make-state {'x 100}) ast)]
      (is (= 100 (:value result)) "Should lookup variable x in environment"))))


(deftest test-lambda-closure
  (testing "Lambda creates a closure"
    (let [ast {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
          result (ast-walker/run (ast-walker/make-state {}) ast)]
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
          result (ast-walker/run (ast-walker/make-state {}) ast)]
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
          result (ast-walker/run (ast-walker/make-state {'+ add-fn}) ast)]
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
          result (ast-walker/run (ast-walker/make-state {'+ add-fn}) ast)]
      (is (= 6 (:value result)) "Should increment 5 to 6"))))


(deftest test-all-arithmetic-primitives
  (testing "All arithmetic primitive operations"
    (let [state (ast-walker/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (is (= 30 (:value (ast-walker/run state (literal-ast '+ 10 20))))
          "Addition: 10 + 20 = 30")
      (is (= 5 (:value (ast-walker/run state (literal-ast '- 15 10))))
          "Subtraction: 15 - 10 = 5")
      (is (= 50 (:value (ast-walker/run state (literal-ast '* 5 10))))
          "Multiplication: 5 * 10 = 50")
      (is (= 4 (:value (ast-walker/run state (literal-ast '/ 20 5))))
          "Division: 20 / 5 = 4"))))


(deftest test-comparison-operations
  (testing "Comparison primitive operations"
    (let [state (ast-walker/make-state {})
          literal-ast (fn [op-sym a b]
                        (let [op-fn (get vm/primitives op-sym)]
                          {:type :application,
                           :operator {:type :literal, :value op-fn},
                           :operands [{:type :literal, :value a}
                                      {:type :literal, :value b}]}))]
      (is (true? (:value (ast-walker/run state (literal-ast '= 5 5))))
          "Equality: 5 = 5")
      (is (false? (:value (ast-walker/run state (literal-ast '= 5 6))))
          "Inequality: 5 ≠ 6")
      (is (true? (:value (ast-walker/run state (literal-ast '< 3 5))))
          "Less than: 3 < 5")
      (is (true? (:value (ast-walker/run state (literal-ast '> 10 5))))
          "Greater than: 10 > 5"))))


(deftest test-various-primitive-additions
  (testing "Various addition cases"
    (let [add-fn (get vm/primitives '+)
          add-ast (fn [a b]
                    {:type :application,
                     :operator {:type :literal, :value add-fn},
                     :operands [{:type :literal, :value a}
                                {:type :literal, :value b}]})]
      (is (= 0 (:value (ast-walker/run (ast-walker/make-state {}) (add-ast 0 0))))
          "0 + 0 = 0")
      (is (= 5 (:value (ast-walker/run (ast-walker/make-state {}) (add-ast 2 3))))
          "2 + 3 = 5")
      (is (= 100 (:value (ast-walker/run (ast-walker/make-state {}) (add-ast 50 50))))
          "50 + 50 = 100")
      (is (= 0 (:value (ast-walker/run (ast-walker/make-state {}) (add-ast -5 5))))
          "-5 + 5 = 0")
      (is (= -10 (:value (ast-walker/run (ast-walker/make-state {}) (add-ast -3 -7))))
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
          result (ast-walker/run (ast-walker/make-state {'+ add-fn}) ast)]
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
          result (ast-walker/run (ast-walker/make-state {'+ add-fn, '- sub-fn}) ast)]
      (is (= 14 (:value result)) "Should compute 10 + (5 - 1) = 14"))))
