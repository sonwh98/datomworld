(ns yin.vm.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.register :as register]))


;; =============================================================================
;; Bytecode VM tests (full pipeline through numeric bytecode)
;; =============================================================================

(defn compile-and-run-bc
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (register/ast-datoms->asm datoms)
        compiled (register/assembly->bytecode asm)
        vm (register/create-vm vm/primitives)]
    (-> vm
        (vm/load-program compiled)
        (vm/run)
        (vm/value))))


(defn- load-ast
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (register/ast-datoms->asm datoms)
        compiled (register/assembly->bytecode asm)]
    (-> (register/create-vm vm/primitives)
        (vm/load-program compiled))))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (register/create-vm)]
      (is (= {} (vm/store vm)))
      (is (nil? (vm/continuation vm)))))
  (testing "After load-program, control has bytecode"
    (let [vm (load-ast {:type :literal, :value 42})
          ctrl (vm/control vm)]
      (is (= 0 (:ip ctrl)))
      (is (seq (:bytecode ctrl)))))
  (testing "After run, continuation is nil and store is empty"
    (let [vm (-> (load-ast {:type :literal, :value 42})
                 (vm/run))]
      (is (nil? (vm/continuation vm)))
      (is (= {} (vm/store vm)))
      (is (= 42 (vm/value vm)))))
  (testing "Environment contains primitives when provided"
    (let [vm (register/create-vm vm/primitives)]
      (is (fn? (get (vm/environment vm) '+))))))


(deftest bytecode-basic-test
  (testing "Literal via bytecode"
    (is (= 42 (compile-and-run-bc {:type :literal, :value 42})))))


(deftest bytecode-arithmetic-test
  (testing "Addition (+ 10 20) via bytecode"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run-bc ast))))))


(deftest bytecode-conditional-test
  (testing "If-else via bytecode"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run-bc ast)))))
  (testing "If-else (false case) via bytecode"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run-bc ast))))))


(deftest bytecode-lambda-test
  (testing "Lambda Application ((fn [x] (+ x 1)) 10) via bytecode"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run-bc ast))))))


(deftest lambda-closure-test
  (testing "Lambda creates a closure"
    (let [closure (compile-and-run-bc {:type :lambda,
                                       :params ['x],
                                       :body {:type :variable, :name 'x}})]
      (is (= :closure (:type closure)))
      (is (= ['x] (:params closure))))))


(deftest bytecode-nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3)) via bytecode"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run-bc ast))))))


(deftest multi-param-lambda-test
  (testing "Lambda with two parameters ((fn [x y] (+ x y)) 3 5)"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x 'y],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :variable, :name 'y}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 5}]}]
      (is (= 8 (compile-and-run-bc ast))))))


(deftest all-arithmetic-primitives-test
  (testing "All arithmetic primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (= 30 (compile-and-run-bc (binop '+ 10 20))))
      (is (= 5 (compile-and-run-bc (binop '- 15 10))))
      (is (= 50 (compile-and-run-bc (binop '* 5 10))))
      (is (= 4 (compile-and-run-bc (binop '/ 20 5)))))))


(deftest comparison-operations-test
  (testing "Comparison primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (true? (compile-and-run-bc (binop '= 5 5))))
      (is (false? (compile-and-run-bc (binop '= 5 6))))
      (is (true? (compile-and-run-bc (binop '< 3 5))))
      (is (true? (compile-and-run-bc (binop '> 10 5)))))))


(deftest addition-edge-cases-test
  (testing "Addition edge cases"
    (let [add (fn [a b]
                {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value a}
                            {:type :literal, :value b}]})]
      (is (= 0 (compile-and-run-bc (add 0 0))))
      (is (= 0 (compile-and-run-bc (add -5 5))))
      (is (= -10 (compile-and-run-bc (add -3 -7)))))))


(deftest nested-lambda-test
  (testing
    "Nested lambda with closure capture ((fn [x] ((fn [y] (+ x y)) 5)) 3)"
    (let [ast {:type :application,
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
               :operands [{:type :literal, :value 3}]}]
      (is (= 8 (compile-and-run-bc ast))))))


(deftest compound-expression-test
  (testing "Lambda with compound body ((fn [a b] (+ a (- b 1))) 10 5)"
    (let [ast {:type :application,
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
                          {:type :literal, :value 5}]}]
      (is (= 14 (compile-and-run-bc ast))))))
