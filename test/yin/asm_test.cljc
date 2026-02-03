(ns yin.asm-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.asm :as asm]
            [yin.vm :as vm]))


(def primitives {'+ +, '- -, '* *, '/ /})


;; Helper: compile AST map to {:node root-id :datoms datoms-vec}
(defn compile-to-datoms
  [ast]
  (let [datoms (vm/ast->datoms ast)
        root-id (ffirst datoms)]
    {:node root-id, :datoms datoms}))


;; =============================================================================
;; Semantic Assembly Tests (using :yin/ datoms from vm/ast->datoms)
;; =============================================================================

(deftest semantic-literal-test
  (testing "Literal compilation produces queryable datoms"
    (let [ast {:type :literal, :value 42}
          compiled (compile-to-datoms ast)]
      ;; Verify structure
      (is (contains? compiled :node))
      (is (contains? compiled :datoms))
      ;; Verify execution
      (is (= 42 (asm/run-semantic compiled)))
      ;; Verify queryability
      (let [node-attrs (asm/get-node-attrs (:datoms compiled) (:node compiled))]
        (is (= :literal (:yin/type node-attrs)))))))


(deftest semantic-variable-test
  (testing "Variable lookup with semantic assembly"
    (let [ast {:type :variable, :name 'a}
          compiled (compile-to-datoms ast)]
      (is (= 100 (asm/run-semantic compiled {'a 100})))
      ;; Query: find all variable references
      (is (= 1 (count (asm/find-variables (:datoms compiled))))))))


(deftest semantic-application-test
  (testing "Application preserves semantic structure"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          compiled (compile-to-datoms ast)]
      ;; Execution
      (is (= 30 (asm/run-semantic compiled primitives)))
      ;; Query: find all applications
      (let [apps (asm/find-applications (:datoms compiled))]
        (is (= 1 (count apps)))))))


(deftest semantic-lambda-test
  (testing "Lambda preserves closure semantics"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}
          compiled (compile-to-datoms ast)]
      ;; Execution
      (is (= 11 (asm/run-semantic compiled primitives)))
      ;; Query: find all lambdas
      (let [lambdas (asm/find-lambdas (:datoms compiled))]
        (is (= 1 (count lambdas)))
        ;; Verify lambda attributes are preserved
        (let [lambda-attrs (asm/get-node-attrs (:datoms compiled)
                                               (first lambdas))]
          (is (= ['x] (:yin/params lambda-attrs))))))))


(deftest semantic-conditional-test
  (testing "Conditional with semantic assembly (true branch)"
    (let [ast {:type :if,
               :test {:type :literal, :value true},
               :consequent {:type :literal, :value :yes},
               :alternate {:type :literal, :value :no}}
          compiled (compile-to-datoms ast)]
      (is (= :yes (asm/run-semantic compiled)))))
  (testing "Conditional with semantic assembly (false branch)"
    (let [ast {:type :if,
               :test {:type :literal, :value false},
               :consequent {:type :literal, :value :yes},
               :alternate {:type :literal, :value :no}}
          compiled (compile-to-datoms ast)]
      (is (= :no (asm/run-semantic compiled))))))


;; =============================================================================
;; Query Tests: The Key Insight
;; =============================================================================
;; The key point: semantic assembly enables high-level queries
;; that would be impossible with numeric assembly.

(deftest query-all-operations-test
  (testing "Can query all function applications in assembly"
    (let [;; Complex expression: ((fn [x] (+ x (* 2 3))) 10)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands
                                   [{:type :variable, :name 'x}
                                    {:type :application,
                                     :operator {:type :variable, :name '*},
                                     :operands [{:type :literal, :value 2}
                                                {:type :literal, :value 3}]}]}},
               :operands [{:type :literal, :value 10}]}
          compiled (compile-to-datoms ast)]
      ;; Execution
      (is (= 16 (asm/run-semantic compiled primitives)))
      ;; Query: find all applications (should be 3: outer call, +, *)
      (is (= 3 (count (asm/find-applications (:datoms compiled)))))
      ;; Query: find all variables
      (is (= 3 (count (asm/find-variables (:datoms compiled)))))
      ;; Query: find all lambdas
      (is (= 1 (count (asm/find-lambdas (:datoms compiled))))))))


(deftest query-by-attribute-test
  (testing "Can query assembly by semantic attributes"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['a 'b],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'a}
                                            {:type :variable, :name 'b}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 4}]}
          compiled (compile-to-datoms ast)]
      ;; Execution
      (is (= 7 (asm/run-semantic compiled primitives)))
      ;; Query: find lambda with 2 params
      (let [lambda-node (first (asm/find-lambdas (:datoms compiled)))
            attrs (asm/get-node-attrs (:datoms compiled) lambda-node)]
        (is (= ['a 'b] (:yin/params attrs)))))))


;; =============================================================================
;; Stack Assembly Tests
;; =============================================================================

(deftest stack-assembly-literal-test
  (testing "Stack assembly from :yin/ datoms - literal"
    (let [datoms (vm/ast->datoms {:type :literal, :value 42})
          asm-instrs (asm/ast-datoms->stack-assembly datoms)
          [bytes pool] (asm/stack-assembly->bytecode asm-instrs)]
      (is (= 42 (asm/run-bytes bytes pool))))))


(deftest stack-assembly-application-test
  (testing "Stack assembly from :yin/ datoms - application"
    (let [datoms (vm/ast->datoms {:type :application,
                                  :operator {:type :variable, :name '+},
                                  :operands [{:type :literal, :value 10}
                                             {:type :literal, :value 20}]})
          asm-instrs (asm/ast-datoms->stack-assembly datoms)
          [bytes pool] (asm/stack-assembly->bytecode asm-instrs)]
      (is (= 30 (asm/run-bytes bytes pool primitives))))))


(deftest stack-assembly-lambda-test
  (testing "Stack assembly from :yin/ datoms - lambda"
    (let [datoms (vm/ast->datoms
                   {:type :application,
                    :operator {:type :lambda,
                               :params ['x],
                               :body {:type :application,
                                      :operator {:type :variable, :name '+},
                                      :operands [{:type :variable, :name 'x}
                                                 {:type :literal, :value 1}]}},
                    :operands [{:type :literal, :value 10}]})
          asm-instrs (asm/ast-datoms->stack-assembly datoms)
          [bytes pool] (asm/stack-assembly->bytecode asm-instrs)]
      (is (= 11 (asm/run-bytes bytes pool primitives))))))


(deftest stack-assembly-conditional-test
  (testing "Stack assembly from :yin/ datoms - conditional true"
    (let [datoms (vm/ast->datoms {:type :if,
                                  :test {:type :literal, :value true},
                                  :consequent {:type :literal, :value :yes},
                                  :alternate {:type :literal, :value :no}})
          asm-instrs (asm/ast-datoms->stack-assembly datoms)
          [bytes pool] (asm/stack-assembly->bytecode asm-instrs)]
      (is (= :yes (asm/run-bytes bytes pool)))))
  (testing "Stack assembly from :yin/ datoms - conditional false"
    (let [datoms (vm/ast->datoms {:type :if,
                                  :test {:type :literal, :value false},
                                  :consequent {:type :literal, :value :yes},
                                  :alternate {:type :literal, :value :no}})
          asm-instrs (asm/ast-datoms->stack-assembly datoms)
          [bytes pool] (asm/stack-assembly->bytecode asm-instrs)]
      (is (= :no (asm/run-bytes bytes pool))))))
