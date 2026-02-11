(ns yin.vm.stack-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.stack :as stack]))


;; =============================================================================
;; Stack VM tests (full pipeline through numeric bytecode via protocols)
;; =============================================================================

(defn compile-and-run
  [ast]
  (let [datoms (vm/ast->datoms ast)
        asm (stack/ast-datoms->asm datoms)
        compiled (stack/assembly->bytecode asm)
        vm (stack/create-vm vm/primitives)]
    (-> vm
        (vm/load-program compiled)
        (vm/run)
        (vm/value))))


(deftest literal-test
  (testing "Literal via stack VM"
    (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest arithmetic-test
  (testing "Addition (+ 10 20) via stack VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest conditional-test
  (testing "If-else (true case) via stack VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case) via stack VM"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest lambda-test
  (testing "Lambda application ((fn [x] (+ x 1)) 10) via stack VM"
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
  (testing "Nested calls (+ 1 (+ 2 3)) via stack VM"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))
