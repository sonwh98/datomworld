(ns yin.bytecode-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.bytecode :as bc]))

(def primitives
  {'+ +
   '- -
   '* *
   '/ /})

;; =============================================================================
;; Legacy Numeric Bytecode Tests
;; =============================================================================
;; These test the traditional bytecode format for backwards compatibility.

(deftest legacy-literal-test
  (testing "Literal compilation and execution (legacy)"
    (let [ast {:type :literal :value 42}
          [bytes pool] (bc/compile-legacy ast)]
      (is (= 42 (bc/run-bytes bytes pool))))))

(deftest legacy-variable-test
  (testing "Variable lookup (legacy)"
    (let [ast {:type :variable :name 'a}
          [bytes pool] (bc/compile-legacy ast)]
      (is (= 100 (bc/run-bytes bytes pool {'a 100}))))))

(deftest legacy-application-test
  (testing "Primitive application (+ 10 20) (legacy)"
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          [bytes pool] (bc/compile-legacy ast)]
      (is (= 30 (bc/run-bytes bytes pool primitives))))))

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
          [bytes pool] (bc/compile-legacy ast)]
      (is (= 11 (bc/run-bytes bytes pool primitives))))))

(deftest legacy-conditional-test
  (testing "If true (legacy)"
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (bc/compile-legacy ast)]
      (is (= :yes (bc/run-bytes bytes pool)))))

  (testing "If false (legacy)"
    (let [ast {:type :if
               :test {:type :literal :value false}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          [bytes pool] (bc/compile-legacy ast)]
      (is (= :no (bc/run-bytes bytes pool))))))

;; =============================================================================
;; Semantic Bytecode Tests
;; =============================================================================
;; These test the new datom-based semantic bytecode.
;; Key difference: bytecode is queryable and preserves semantic information.

(deftest semantic-literal-test
  (testing "Literal compilation produces queryable datoms"
    (bc/reset-node-counter!)
    (let [ast {:type :literal :value 42}
          compiled (bc/compile ast)]
      ;; Verify structure
      (is (contains? compiled :node))
      (is (contains? compiled :datoms))
      (is (contains? compiled :pool))
      ;; Verify execution
      (is (= 42 (bc/run-semantic compiled)))
      ;; Verify queryability
      (let [node-attrs (bc/get-node-attrs (:datoms compiled) (:node compiled))]
        (is (= :literal (:op/type node-attrs)))
        (is (= :number (:op/value-type node-attrs)))))))

(deftest semantic-variable-test
  (testing "Variable lookup with semantic bytecode"
    (bc/reset-node-counter!)
    (let [ast {:type :variable :name 'a}
          compiled (bc/compile ast)]
      (is (= 100 (bc/run-semantic compiled {'a 100})))
      ;; Query: find all variable references
      (is (= 1 (count (bc/find-variables (:datoms compiled))))))))

(deftest semantic-application-test
  (testing "Application preserves semantic structure"
    (bc/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :variable :name '+}
               :operands [{:type :literal :value 10}
                          {:type :literal :value 20}]}
          compiled (bc/compile ast)]
      ;; Execution
      (is (= 30 (bc/run-semantic compiled primitives)))
      ;; Query: find all applications
      (let [apps (bc/find-applications (:datoms compiled))]
        (is (= 1 (count apps))))
      ;; Query: verify arity is preserved
      (let [app-attrs (bc/get-node-attrs (:datoms compiled) (:node compiled))]
        (is (= 2 (:op/arity app-attrs)))))))

(deftest semantic-lambda-test
  (testing "Lambda preserves closure semantics"
    (bc/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['x]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'x}
                                            {:type :literal :value 1}]}}
               :operands [{:type :literal :value 10}]}
          compiled (bc/compile ast)]
      ;; Execution
      (is (= 11 (bc/run-semantic compiled primitives)))
      ;; Query: find all lambdas
      (let [lambdas (bc/find-lambdas (:datoms compiled))]
        (is (= 1 (count lambdas)))
        ;; Verify lambda attributes are preserved
        (let [lambda-attrs (bc/get-node-attrs (:datoms compiled) (first lambdas))]
          (is (= 1 (:op/arity lambda-attrs)))
          (is (= ['x] (:op/params lambda-attrs)))
          (is (true? (:op/captures-env? lambda-attrs))))))))

(deftest semantic-conditional-test
  (testing "Conditional with semantic bytecode (true branch)"
    (bc/reset-node-counter!)
    (let [ast {:type :if
               :test {:type :literal :value true}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          compiled (bc/compile ast)]
      (is (= :yes (bc/run-semantic compiled)))))

  (testing "Conditional with semantic bytecode (false branch)"
    (bc/reset-node-counter!)
    (let [ast {:type :if
               :test {:type :literal :value false}
               :consequent {:type :literal :value :yes}
               :alternate {:type :literal :value :no}}
          compiled (bc/compile ast)]
      (is (= :no (bc/run-semantic compiled))))))

;; =============================================================================
;; Query Tests: The Key Insight
;; =============================================================================
;; The blog's key point: semantic bytecode enables high-level queries
;; that would be impossible with numeric bytecode.

(deftest query-all-operations-test
  (testing "Can query all function applications in bytecode"
    (bc/reset-node-counter!)
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
          compiled (bc/compile ast)]
      ;; Execution
      (is (= 16 (bc/run-semantic compiled primitives)))
      ;; Query: find all applications (should be 3: outer call, +, *)
      (is (= 3 (count (bc/find-applications (:datoms compiled)))))
      ;; Query: find all variables
      (is (= 3 (count (bc/find-variables (:datoms compiled)))))
      ;; Query: find all lambdas
      (is (= 1 (count (bc/find-lambdas (:datoms compiled))))))))

(deftest query-by-attribute-test
  (testing "Can query bytecode by semantic attributes"
    (bc/reset-node-counter!)
    (let [ast {:type :application
               :operator {:type :lambda
                          :params ['a 'b]
                          :body {:type :application
                                 :operator {:type :variable :name '+}
                                 :operands [{:type :variable :name 'a}
                                            {:type :variable :name 'b}]}}
               :operands [{:type :literal :value 3}
                          {:type :literal :value 4}]}
          compiled (bc/compile ast)]
      ;; Execution
      (is (= 7 (bc/run-semantic compiled primitives)))
      ;; Query: find all nodes with arity attribute
      (let [arity-datoms (bc/find-by-attr (:datoms compiled) :op/arity)]
        (is (>= (count arity-datoms) 2))) ; lambda and application both have arity
      ;; Query: find lambda with 2 params
      (let [lambda-node (first (bc/find-lambdas (:datoms compiled)))
            attrs (bc/get-node-attrs (:datoms compiled) lambda-node)]
        (is (= 2 (:op/arity attrs)))
        (is (= ['a 'b] (:op/params attrs)))))))
