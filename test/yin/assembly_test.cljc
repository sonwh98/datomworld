(ns yin.assembly-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.assembly :as asm]))

(def primitives
  {'+ +
   '- -
   '* *
   '/ /})

;; =============================================================================
;; Legacy Numeric Assembly Tests
;; =============================================================================
;; These test the traditional assembly format for backwards compatibility.

(deftest legacy-literal-test
  (testing "Literal compilation and execution (legacy)"
    (let [ast {:type :literal :value 42}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= 42 (asm/run-bytes bytes pool))))))

(deftest legacy-variable-test
  (testing "Variable lookup (legacy)"
    (let [ast {:type :variable :name 'a}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= 100 (asm/run-bytes bytes pool {'a 100}))))))

(deftest legacy-application-test
  (testing "Primitive application (+ 10 20) (legacy)"
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= 30 (asm/run-bytes bytes pool primitives))))))

(deftest legacy-lambda-test
  (testing "Lambda definition and application ((fn [x] (+ x 1)) 10) (legacy)"
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :literal :value 1}]}}
               :operands [{:type :literal :value 10}]}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= 11 (asm/run-bytes bytes pool primitives))))))

(deftest legacy-conditional-test
  (testing "If true (legacy)"
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= :yes (asm/run-bytes bytes pool)))))

  (testing "If false (legacy)"
    (let [ast {:type :if
               :test {:type :literal :value false}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (asm/compile-legacy ast)]
      (is (= :no (asm/run-bytes bytes pool))))))

;; =============================================================================
;; Semantic Assembly Tests
;; =============================================================================
;; These test the new datom-based semantic assembly.
;; Key difference: assembly is queryable and preserves semantic information.

(deftest semantic-literal-test
  (testing "Literal compilation produces queryable datoms"
    (asm/reset-node-counter!)
    (let [ast {:type :literal :value 42}
          compiled (asm/compile ast)]
      ;; Verify structure
      (is (contains? compiled :node))
      (is (contains? compiled :datoms))
      (is (contains? compiled :pool))
      ;; Verify execution
      (is (= 42 (asm/run-semantic compiled)))
      ;; Verify queryability
      (let [node-attrs (asm/get-node-attrs (:datoms compiled) (:node compiled))]
        (is (= :literal (:op/type node-attrs)))
        (is (= :number (:op/value-type node-attrs)))))))

(deftest semantic-variable-test
  (testing "Variable lookup with semantic assembly"
    (asm/reset-node-counter!)
    (let [ast {:type :variable :name 'a}
          compiled (asm/compile ast)]
      (is (= 100 (asm/run-semantic compiled {'a 100})))
      ;; Query: find all variable references
      (is (= 1 (count (asm/find-variables (:datoms compiled))))))))

(deftest semantic-application-test
  (testing "Application preserves semantic structure"
    (asm/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          compiled (asm/compile ast)]
      ;; Execution
      (is (= 30 (asm/run-semantic compiled primitives)))
      ;; Query: find all applications
      (let [apps (asm/find-applications (:datoms compiled))]
        (is (= 1 (count apps))))
      ;; Query: verify arity is preserved
      (let [app-attrs (asm/get-node-attrs (:datoms compiled) (:node compiled))]
        (is (= 2 (:op/arity app-attrs)))))))

(deftest semantic-lambda-test
  (testing "Lambda preserves closure semantics"
    (asm/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :literal :value 1}]}}
               :operands [{:type :literal :value 10}]}
          compiled (asm/compile ast)]
      ;; Execution
      (is (= 11 (asm/run-semantic compiled primitives)))
      ;; Query: find all lambdas
      (let [lambdas (asm/find-lambdas (:datoms compiled))]
        (is (= 1 (count lambdas)))
        ;; Verify lambda attributes are preserved
        (let [lambda-attrs (asm/get-node-attrs (:datoms compiled) (first lambdas))]
          (is (= 1 (:op/arity lambda-attrs)))
          (is (= ['x] (:op/params lambda-attrs)))
          (is (true? (:op/captures-env? lambda-attrs))))))))

(deftest semantic-conditional-test
  (testing "Conditional with semantic assembly (true branch)"
    (asm/reset-node-counter!)
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          compiled (asm/compile ast)]
      (is (= :yes (asm/run-semantic compiled)))))

  (testing "Conditional with semantic assembly (false branch)"
    (asm/reset-node-counter!)
    (let [ast {:type :if
               :test {:type :literal :value false}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          compiled (asm/compile ast)]
      (is (= :no (asm/run-semantic compiled))))))

;; =============================================================================
;; Query Tests: The Key Insight
;; =============================================================================
;; The blog's key point: semantic assembly enables high-level queries
;; that would be impossible with numeric assembly.

(deftest query-all-operations-test
  (testing "Can query all function applications in assembly"
    (asm/reset-node-counter!)
    (let [;; Complex expression: ((fn [x] (+ x (* 2 3))) 10)
          ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :application
                                             :operator {:type :variable :name '*}
                                             :operands [{:type :literal :value 2}
                                                        {:type :literal :value 3}]}]}}
               :operands [{:type :literal :value 10}]}
          compiled (asm/compile ast)]
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
    (asm/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['a 'b]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'a}
                                            {:type :variable :name 'b}]}}
               :operands [{:type :literal :value 3}
                          {:type :literal :value 4}]}
          compiled (asm/compile ast)]
      ;; Execution
      (is (= 7 (asm/run-semantic compiled primitives)))
      ;; Query: find all nodes with arity attribute
      (let [arity-datoms (asm/find-by-attr (:datoms compiled) :op/arity)]
        (is (>= (count arity-datoms) 2))) ; lambda and application both have arity
      ;; Query: find lambda with 2 params
      (let [lambda-node (first (asm/find-lambdas (:datoms compiled)))
            attrs (asm/get-node-attrs (:datoms compiled) lambda-node)]
        (is (= 2 (:op/arity attrs)))
        (is (= ['a 'b] (:op/params attrs)))))))
