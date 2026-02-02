(ns yin.vm-continuation-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]))


(deftest test-simple-increment
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


(deftest test-continuation-step-count
  (testing "Count execution steps with continuations"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)
          step-count (loop [state initial-state
                            steps 0]
                       (if (or (:control state) (:continuation state))
                         (recur (vm/eval state nil) (inc steps))
                         steps))]
      (is (= 17 step-count) "Should take exactly 17 steps to evaluate"))))


(deftest test-continuation-state-capture
  (testing "Capture and inspect continuation states"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)
          ;; Collect all states during execution
          all-states (loop [state initial-state
                            states []]
                       (if (or (:control state) (:continuation state))
                         (recur (vm/eval state nil) (conj states state))
                         (conj states state)))]
      (testing "First state has control"
        (is (some? (:control (first all-states)))
            "Initial state should have control"))
      (testing "Final state has result"
        (is (= 6 (:value (last all-states)))
            "Final state should contain result value 6"))
      (testing "Intermediate states have continuations"
        (is (some #(:continuation %) all-states)
            "Some intermediate states should have active continuations"))
      (testing "All states are immutable snapshots"
        (is (every? map? all-states) "Every state should be a map")
        (is (every? #(contains? % :control) all-states)
            "Every state should have a control field")))))


(deftest test-increment-by-two
  (testing "Increment by 2 using nested addition"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x) (+ (+ x 1) 1)) 10)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands
                                   [{:type :application,
                                     :operator {:type :variable, :name '+},
                                     :operands [{:type :variable, :name 'x}
                                                {:type :literal, :value 1}]}
                                    {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}
          result (vm/run (vm/make-state {'+ add-fn}) ast)]
      (is (= 12 (:value result)) "Should increment 10 by 2 to get 12"))))


(deftest test-increment-multiple-values
  (testing "Increment different starting values"
    (let [add-fn (get vm/primitives '+)
          increment-ast (fn [n]
                          {:type :application,
                           :operator
                             {:type :lambda,
                              :params ['x],
                              :body {:type :application,
                                     :operator {:type :variable, :name '+},
                                     :operands [{:type :variable, :name 'x}
                                                {:type :literal, :value 1}]}},
                           :operands [{:type :literal, :value n}]})]
      (is (= 1 (:value (vm/run (vm/make-state {'+ add-fn}) (increment-ast 0))))
          "0 + 1 = 1")
      (is (= 6 (:value (vm/run (vm/make-state {'+ add-fn}) (increment-ast 5))))
          "5 + 1 = 6")
      (is (= 101
             (:value (vm/run (vm/make-state {'+ add-fn}) (increment-ast 100))))
          "100 + 1 = 101")
      (is (= 0 (:value (vm/run (vm/make-state {'+ add-fn}) (increment-ast -1))))
          "-1 + 1 = 0"))))


(deftest test-continuation-type-transitions
  (testing "Verify continuation type transitions during evaluation"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)
          ;; Collect continuation types during execution
          continuation-types (loop [state initial-state
                                    types []]
                               (if (or (:control state) (:continuation state))
                                 (recur (vm/eval state nil)
                                        (conj types
                                              (:type (:continuation state))))
                                 types))
          unique-types (set (filter some? continuation-types))]
      (testing "Contains eval-operator continuation"
        (is (contains? unique-types :eval-operator)
            "Should have :eval-operator continuation type"))
      (testing "Contains eval-operand continuation"
        (is (contains? unique-types :eval-operand)
            "Should have :eval-operand continuation type"))
      (testing "Starts with nil continuation"
        (is (nil? (first continuation-types))
            "Initial state should have no continuation")))))
