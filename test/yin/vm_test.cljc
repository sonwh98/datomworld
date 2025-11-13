(ns yin.vm-test
  (:require [yin.vm :as vm]
            [yin.test-util :refer [make-state]]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest test-literal-evaluation
  (testing "Integer literal evaluation"
    (let [ast {:type :literal :value 42}
          result (vm/run (make-state {}) ast)]
      (is (= 42 (:value result))
          "Integer literal should evaluate to itself")))

  (testing "String literal evaluation"
    (let [ast {:type :literal :value "hello"}
          result (vm/run (make-state {}) ast)]
      (is (= "hello" (:value result))
          "String literal should evaluate to itself"))))

(deftest test-variable-lookup
  (testing "Single variable lookup in environment"
    (let [ast {:type :variable :name 'x}
          result (vm/run (make-state {'x 100}) ast)]
      (is (= 100 (:value result))
          "Should lookup variable x in environment")))

  (testing "Multiple variables in environment"
    (let [ast {:type :variable :name 'z}
          result (vm/run (make-state {'x 10 'y 20 'z 30}) ast)]
      (is (= 30 (:value result))
          "Should lookup variable z from environment with multiple bindings"))))

(deftest test-lambda-closure
  (testing "Lambda creates a closure"
    (let [ast {:type :lambda
               :params ['x]
               :body {:type :variable :name 'x}}
          result (vm/run (make-state {'y 50}) ast)]
      (is (= :closure (:type (:value result)))
          "Lambda should create a closure")
      (is (= ['x] (:params (:value result)))
          "Closure should have correct parameters"))))

(deftest test-conditional-evaluation
  (testing "Conditional initial evaluation step"
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value "yes"}
               :alternate {:type :literal :value "no"}}
          result (vm/eval (make-state {}) ast)]
      (is (= {:type :literal :value true} (:control result))
          "First step should evaluate the test expression"))))

(deftest test-primitive-addition
  (testing "Direct primitive addition"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application
               :operator {:type :literal :value add-fn}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          result (vm/run (make-state {}) ast)]
      (is (= 30 (:value result))
          "Direct call to + primitive: 10 + 20 = 30"))))

(deftest test-lambda-application
  (testing "Lambda that adds two numbers"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x y) (+ x y)) 3 5)
          ast {:type :application
               :operator {:type :lambda
                          :params ['x 'y]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :variable :name 'y}]}}
               :operands [{:type :literal :value 3}
                          {:type :literal :value 5}]}
          result (vm/run (make-state {'+ add-fn}) ast)]
      (is (= 8 (:value result))
          "Lambda application: ((lambda (x y) (+ x y)) 3 5) = 8"))))

(deftest test-continuation-stepping
  (testing "Continuation-based evaluation with step counting"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x) (+ x 1)) 5)
          ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :literal :value 1}]}}
               :operands [{:type :literal :value 5}]}
          initial-state (assoc (make-state {'+ add-fn}) :control ast)]

      (testing "Multiple evaluation steps occur"
        (let [step-count (loop [state initial-state
                                steps 0]
                           (if (or (:control state) (:continuation state))
                             (recur (vm/eval state nil) (inc steps))
                             steps))]
          (is (> step-count 5)
              "Should take multiple steps to evaluate lambda application")))

      (testing "Final result is correct"
        (let [result (vm/run (make-state {'+ add-fn}) ast)]
          (is (= 6 (:value result))
              "((lambda (x) (+ x 1)) 5) should evaluate to 6"))))))

(comment
  ;; REPL usage examples:

  ;; Run all tests
  #?(:clj (clojure.test/run-tests 'yin.vm-test)
     :cljs (cljs.test/run-tests 'yin.vm-test))

  ;; Run individual tests
  (test-literal-evaluation)
  (test-variable-lookup)
  (test-lambda-closure)
  (test-primitive-addition)
  (test-lambda-application)
  (test-continuation-stepping)

  ;; Manual REPL exploration:

  ;; Step through evaluation manually
  (def my-state {:control {:type :literal :value 42}
                 :environment {}
                 :store {}
                 :continuation nil
                 :value nil})

  (vm/eval my-state nil)

  ;; Run to completion
  (vm/run (make-state {})
          {:type :literal :value 99})

  ;; Test lambda addition manually
  (def add-fn (get vm/primitives '+))
  (def add-lambda-ast
    {:type :application
     :operator {:type :lambda
                :params ['x 'y]
                :body {:type :application
                       :operator {:type :variable :name '+}
                       :operands [{:type :variable :name 'x}
                                  {:type :variable :name 'y}]}}
     :operands [{:type :literal :value 3}
                {:type :literal :value 5}]})
  (vm/run (make-state {'+ add-fn}) add-lambda-ast))
