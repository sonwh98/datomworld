(ns yin.vm.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.protocols :as proto]
            [yin.vm.register :as register]))


(defn compile-and-run
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (register/ast-datoms->asm datoms)
        vm (register/create vm/primitives)]
    (-> vm
        (proto/load-program asm)
        (proto/run)
        (proto/value))))


(deftest compiler-basic-test
  (testing "Literal" (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest compiler-arithmetic-test
  (testing "Addition (+ 10 20)"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest compiler-conditional-test
  (testing "If-else"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case)"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest compiler-lambda-test
  (testing "Lambda Application ((fn [x] (+ x 1)) 10)"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run ast))))))


(deftest compiler-nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3))"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))


;; =============================================================================
;; Bytecode VM tests (full pipeline through numeric bytecode)
;; =============================================================================

(defn compile-and-run-bc
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (register/ast-datoms->asm datoms)
        compiled (register/register-assembly->bytecode asm)
        vm (register/create vm/primitives)]
    (-> vm
        (proto/load-program compiled)
        (proto/run)
        (proto/value))))


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


(comment
  ;; Example: Creating a RegisterVM and loading/running a program manually
  (let [vm (register/create vm/primitives)
        ;; Symbolic program
        program [[:loadk 0 42] [:return 0]]
        vm-loaded (proto/load-program vm program)
        vm-final (proto/run vm-loaded)]
    (proto/value vm-final))
  ;; => 42
)
