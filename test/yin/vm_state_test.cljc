(ns yin.vm-state-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]))


(deftest test-state-structure
  (testing "State has all required fields"
    (let [state (vm/make-state {})]
      (is (contains? state :control) "State should have :control field")
      (is (contains? state :environment) "State should have :environment field")
      (is (contains? state :store) "State should have :store field")
      (is (contains? state :continuation)
          "State should have :continuation field")
      (is (contains? state :value) "State should have :value field"))))


(deftest test-initial-state
  (testing "Initial state has correct default values"
    (let [state (vm/make-state {})]
      (is (nil? (:control state)) "Initial control should be nil")
      (is (empty? (:environment state)) "Initial environment should be empty")
      (is (empty? (:store state)) "Initial store should be empty")
      (is (nil? (:continuation state)) "Initial continuation should be nil")
      (is (nil? (:value state)) "Initial value should be nil"))))


(deftest test-state-with-environment
  (testing "State can have initial environment bindings"
    (let [state (vm/make-state {'x 10, 'y 20})]
      (is (= 10 (get-in state [:environment 'x]))
          "Environment should contain x binding")
      (is (= 20 (get-in state [:environment 'y]))
          "Environment should contain y binding"))))


(deftest test-state-immutability
  (testing "State is immutable - eval creates new state"
    (let [ast {:type :literal, :value 42}
          state-0 (assoc (vm/make-state {}) :control ast)
          state-1 (vm/eval state-0 nil)]
      (is (not= state-0 state-1) "Eval should create a new state")
      (is (= ast (:control state-0))
          "Original state control should be unchanged")
      (is (nil? (:value state-0)) "Original state value should be unchanged")
      (is (nil? (:control state-1)) "New state control should be nil")
      (is (= 42 (:value state-1)) "New state value should be 42"))))


(deftest test-state-transitions-literal
  (testing "State transitions for literal evaluation"
    (let [ast {:type :literal, :value 42}
          state-0 (assoc (vm/make-state {}) :control ast)]
      (testing "Initial state"
        (is (some? (:control state-0)) "Initial state should have control")
        (is (nil? (:value state-0)) "Initial state should have no value"))
      (testing "After one step"
        (let [state-1 (vm/eval state-0 nil)]
          (is (nil? (:control state-1)) "After eval, control should be nil")
          (is (= 42 (:value state-1)) "After eval, value should be 42"))))))


(deftest test-state-transitions-with-continuation
  (testing "State transitions with continuations"
    (let [add-fn (get vm/primitives '+)
          ;; ((lambda (x) (+ x 1)) 5)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)
          ;; Collect all states
          all-states (loop [state initial-state
                            states []]
                       (if (or (:control state) (:continuation state))
                         (recur (vm/eval state nil) (conj states state))
                         (conj states state)))]
      (testing "Initial state has control"
        (is (some? (:control (first all-states)))
            "First state should have control"))
      (testing "Some intermediate states have continuations"
        (is (some #(:continuation %) all-states)
            "Some states should have active continuations"))
      (testing "Final state has result"
        (let [final-state (last all-states)]
          (is (nil? (:control final-state)) "Final state control should be nil")
          (is (nil? (:continuation final-state))
              "Final state continuation should be nil")
          (is (= 6 (:value final-state))
              "Final state should have result value 6")))
      (testing "Multiple state transitions occur"
        (is (> (count all-states) 5)
            "Should have multiple state transitions")))))


(deftest test-continuation-types
  (testing "Different continuation types are used"
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
          ;; Collect continuation types
          cont-types (loop [state initial-state
                            types #{}]
                       (if (or (:control state) (:continuation state))
                         (let [cont-type (:type (:continuation state))
                               new-types
                                 (if cont-type (conj types cont-type) types)]
                           (recur (vm/eval state nil) new-types))
                         types))]
      (is (contains? cont-types :eval-operator)
          "Should use :eval-operator continuation")
      (is (contains? cont-types :eval-operand)
          "Should use :eval-operand continuation"))))


(deftest test-state-captures-computation
  (testing "State captures entire computational context"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          state (assoc (vm/make-state {'+ add-fn}) :control ast)]
      (testing "State captures control"
        (is (= :application (:type (:control state)))
            "State should capture the AST being evaluated"))
      (testing "State captures environment"
        (is (contains? (:environment state) '+)
            "State should capture the environment bindings"))
      (testing "State structure can be inspected"
        (is (map? state) "State should be a map")
        (is (= #{:control :environment :store :continuation :value}
               (set (keys state)))
            "State should have all CESK fields")))))


(deftest test-state-step-count
  (testing "Count evaluation steps via state transitions"
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


(deftest test-environment-extension
  (testing "Environment is extended during lambda application"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :variable, :name 'x}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)
          ;; Find state where environment has 'x bound
          states (loop [state initial-state
                        collected []]
                   (if (or (:control state) (:continuation state))
                     (recur (vm/eval state nil) (conj collected state))
                     (conj collected state)))
          state-with-x (some #(when (contains? (:environment %) 'x) %) states)]
      (is (some? state-with-x) "Should find a state with x in environment")
      (when state-with-x
        (is (= 5 (get-in state-with-x [:environment 'x]))
            "x should be bound to 5 in extended environment")))))
