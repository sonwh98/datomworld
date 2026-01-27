(ns yin.vm-test
  (:require [yin.vm :as vm]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest test-literal-evaluation
  (testing "Integer literal evaluation"
    (let [ast {:type :literal :value 42}
          result (vm/run (vm/make-state {}) ast)]
      (is (= 42 (:value result))
          "Integer literal should evaluate to itself")))

  (testing "String literal evaluation"
    (let [ast {:type :literal :value "hello"}
          result (vm/run (vm/make-state {}) ast)]
      (is (= "hello" (:value result))
          "String literal should evaluate to itself"))))

(deftest test-variable-lookup
  (testing "Single variable lookup in environment"
    (let [ast {:type :variable :name 'x}
          result (vm/run (vm/make-state {'x 100}) ast)]
      (is (= 100 (:value result))
          "Should lookup variable x in environment")))

  (testing "Multiple variables in environment"
    (let [ast {:type :variable :name 'z}
          result (vm/run (vm/make-state {'x 10 'y 20 'z 30}) ast)]
      (is (= 30 (:value result))
          "Should lookup variable z from environment with multiple bindings"))))

(deftest test-lambda-closure
  (testing "Lambda creates a closure"
    (let [ast {:type :lambda
               :params ['x]
               :body {:type :variable :name 'x}}
          result (vm/run (vm/make-state {'y 50}) ast)]
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
          result (vm/eval (vm/make-state {}) ast)]
      (is (= {:type :literal :value true} (:control result))
          "First step should evaluate the test expression"))))

(deftest test-primitive-addition
  (testing "Direct primitive addition"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application
               :operator {:type :literal :value add-fn}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          result (vm/run (vm/make-state {}) ast)]
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
          result (vm/run (vm/make-state {'+ add-fn}) ast)]
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
          initial-state (assoc (vm/make-state {'+ add-fn}) :control ast)]

      (testing "Multiple evaluation steps occur"
        (let [step-count (loop [state initial-state
                                steps 0]
                           (if (or (:control state) (:continuation state))
                             (recur (vm/eval state nil) (inc steps))
                             steps))]
          (is (> step-count 5)
              "Should take multiple steps to evaluate lambda application")))

      (testing "Final result is correct"
        (let [result (vm/run (vm/make-state {'+ add-fn}) ast)]
          (is (= 6 (:value result))
              "((lambda (x) (+ x 1)) 5) should evaluate to 6"))))))

;; =============================================================================
;; ast->datoms interface tests
;; =============================================================================
;; These test the CONTRACT, not implementation details:
;; - Datoms are 5-tuples [e a v t m]
;; - Entity IDs are tempids (negative integers)
;; - Attributes use :yin/ namespace
;; - Entity references correctly link parent to child nodes

(deftest test-ast->datoms-shape
  (testing "All datoms are 5-tuples [e a v t m]"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42}))]
      (is (every? #(= 5 (count %)) datoms)
          "Every datom must be a 5-tuple")))

  (testing "Entity IDs are tempids (negative integers)"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42}))]
      (is (every? #(neg-int? (first %)) datoms)
          "Entity ID (position 0) must be a negative integer tempid")))

  (testing "Attributes use :yin/ namespace"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42}))
          attrs (map second datoms)]
      (is (every? #(= "yin" (namespace %)) attrs)
          "All attributes must be in :yin/ namespace")))

  (testing "Transaction ID and metadata default to 0"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42}))]
      (is (every? #(= 0 (nth % 3)) datoms)
          "Transaction ID (position 3) defaults to 0")
      (is (every? #(= 0 (nth % 4)) datoms)
          "Metadata (position 4) defaults to 0 (nil metadata entity)"))))

(deftest test-ast->datoms-entity-references
  (testing "Lambda body is referenced by tempid"
    (let [datoms (vec (vm/ast->datoms {:type :lambda
                                       :params ['x]
                                       :body {:type :variable :name 'x}}))
          lambda-datoms (filter #(= :lambda (nth % 2)) datoms)
          body-ref-datom (first (filter #(= :yin/body (second %)) datoms))]
      (is (= 1 (count lambda-datoms))
          "Should have one lambda node")
      (is (some? body-ref-datom)
          "Lambda should have :yin/body attribute")
      (is (neg-int? (nth body-ref-datom 2))
          ":yin/body value should be a tempid reference (negative integer)")))

  (testing "Application operands are vector of tempid references"
    (let [datoms (vec (vm/ast->datoms {:type :application
                                       :operator {:type :variable :name '+}
                                       :operands [{:type :literal :value 1}
                                                  {:type :literal :value 2}]}))
          operands-datom (first (filter #(= :yin/operands (second %)) datoms))]
      (is (some? operands-datom)
          "Application should have :yin/operands attribute")
      (is (vector? (nth operands-datom 2))
          ":yin/operands should be a vector")
      (is (every? neg-int? (nth operands-datom 2))
          ":yin/operands should contain tempid references (negative integers)"))))

(deftest test-ast->datoms-options
  (testing "Custom transaction ID"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42} {:t 1000}))]
      (is (every? #(= 1000 (nth % 3)) datoms)
          "Transaction ID should be 1000")))

  (testing "Custom metadata entity"
    (let [datoms (vec (vm/ast->datoms {:type :literal :value 42} {:m 5}))]
      (is (every? #(= 5 (nth % 4)) datoms)
          "Metadata entity should be 5"))))

(deftest test-ast->datoms-fibonacci
  (testing "Fibonacci lambda produces valid datoms"
    ;; (lambda (n)
    ;;   (if (< n 2)
    ;;     n
    ;;     (+ (fib (- n 1)) (fib (- n 2)))))
    (let [fib-ast {:type :lambda
                   :params ['n]
                   :body {:type :if
                          :test {:type :application
                                 :operator {:type :variable :name '<}
                                 :operands [{:type :variable :name 'n}
                                            {:type :literal :value 2}]}
                          :consequent {:type :variable :name 'n}
                          :alternate {:type :application
                                      :operator {:type :variable :name '+}
                                      :operands [{:type :application
                                                  :operator {:type :variable :name 'fib}
                                                  :operands [{:type :application
                                                              :operator {:type :variable :name '-}
                                                              :operands [{:type :variable :name 'n}
                                                                         {:type :literal :value 1}]}]}
                                                 {:type :application
                                                  :operator {:type :variable :name 'fib}
                                                  :operands [{:type :application
                                                              :operator {:type :variable :name '-}
                                                              :operands [{:type :variable :name 'n}
                                                                         {:type :literal :value 2}]}]}]}}}
          datoms (vec (vm/ast->datoms fib-ast))]

      (testing "All datoms are valid 5-tuples"
        (is (every? #(= 5 (count %)) datoms)
            "Every datom must be a 5-tuple")
        (is (every? #(neg-int? (first %)) datoms)
            "All entity IDs must be negative integer tempids")
        (is (every? #(= "yin" (namespace (second %))) datoms)
            "All attributes must be in :yin/ namespace"))

      (testing "Contains expected node types"
        (let [types (->> datoms
                         (filter #(= :yin/type (second %)))
                         (map #(nth % 2))
                         frequencies)]
          (is (= 1 (get types :lambda))
              "Should have 1 lambda")
          (is (= 1 (get types :if))
              "Should have 1 if")
          (is (= 6 (get types :application))
              "Should have 6 applications: <, +, fib, -, fib, -")
          (is (= 10 (get types :variable))
              "Should have 10 variables: <, n, n, +, fib, -, n, fib, -, n")
          (is (= 3 (get types :literal))
              "Should have 3 literals: 2, 1, 2")))

      (testing "Entity references form valid graph"
        (let [entity-ids (set (map first datoms))
              refs (->> datoms
                        (filter #(#{:yin/body :yin/operator :yin/test
                                    :yin/consequent :yin/alternate} (second %)))
                        (map #(nth % 2)))]
          (is (every? #(contains? entity-ids %) refs)
              "All entity references should point to existing entities"))))))

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
  (vm/run (vm/make-state {})
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
  (vm/run (vm/make-state {'+ add-fn}) add-lambda-ast))
