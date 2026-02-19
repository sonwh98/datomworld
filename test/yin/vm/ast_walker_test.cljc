(ns yin.vm.ast-walker-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datascript.core :as d]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]))


;; =============================================================================
;; AST Walker VM tests (direct AST interpretation via protocols)
;; =============================================================================

(defn compile-and-run
  [ast]
  (-> (ast-walker/create-vm)
      (vm/load-program ast)
      (vm/run)
      (vm/value)))


(deftest cesk-state-test
  (testing "Initial state"
    (let [vm (ast-walker/create-vm)]
      (is (= {} (vm/store vm)))
      (is (nil? (vm/control vm)))
      (is (nil? (vm/continuation vm)))))
  (testing "After load-program, control is non-nil"
    (let [vm (-> (ast-walker/create-vm)
                 (vm/load-program {:type :literal, :value 42}))]
      (is (some? (vm/control vm)))))
  (testing "After run, continuation is nil and store is empty"
    (let [vm (-> (ast-walker/create-vm)
                 (vm/load-program {:type :literal, :value 42})
                 (vm/run))]
      (is (nil? (vm/continuation vm)))
      (is (= {} (vm/store vm)))
      (is (= 42 (vm/value vm)))))
  (testing "Environment is empty by default (primitives resolved separately)"
    (is (= {} (vm/environment (ast-walker/create-vm))))))


(deftest literal-test
  (testing "Literal via AST walker"
    (is (= 42 (compile-and-run {:type :literal, :value 42})))))


(deftest literal-types-test
  (testing "All value types work as literals"
    (doseq [[desc value] [["string" "hello world"] ["boolean true" true]
                          ["boolean false" false] ["nil" nil] ["float" 3.14159]
                          ["negative" -99] ["vector" [1 2 3]]
                          ["map" {:x 10, :y 20}] ["keyword" :status]
                          ["set" #{1 2}]]]
      (testing desc
        (is (= value (compile-and-run {:type :literal, :value value})))))))


(deftest literal-single-step-test
  (testing "Literal evaluation completes in one step"
    (let [vm (-> (ast-walker/create-vm)
                 (vm/load-program {:type :literal, :value 42})
                 (vm/step))]
      (is (= 42 (vm/value vm)))
      (is (vm/halted? vm))
      (is (nil? (vm/control vm)))
      (is (nil? (vm/continuation vm))))))


(deftest arithmetic-test
  (testing "Addition (+ 10 20) via AST walker"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}]
      (is (= 30 (compile-and-run ast))))))


(deftest conditional-test
  (testing "If-else (true case) via AST walker"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 1}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 100 (compile-and-run ast)))))
  (testing "If-else (false case) via AST walker"
    (let [ast {:type :if,
               :test {:type :application,
                      :operator {:type :variable, :name '<},
                      :operands [{:type :literal, :value 5}
                                 {:type :literal, :value 2}]},
               :consequent {:type :literal, :value 100},
               :alternate {:type :literal, :value 200}}]
      (is (= 200 (compile-and-run ast))))))


(deftest lambda-test
  (testing "Lambda application ((fn [x] (+ x 1)) 10) via AST walker"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}]
      (is (= 11 (compile-and-run ast))))))


(deftest lambda-closure-test
  (testing "Lambda creates a closure"
    (let [closure (compile-and-run {:type :lambda,
                                    :params ['x],
                                    :body {:type :variable, :name 'x}})]
      (is (= :closure (:type closure)))
      (is (= ['x] (:params closure))))))


(deftest nested-call-test
  (testing "Nested calls (+ 1 (+ 2 3)) via AST walker"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 1}
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 2}
                                      {:type :literal, :value 3}]}]}]
      (is (= 6 (compile-and-run ast))))))


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
      (is (= 8 (compile-and-run ast))))))


(deftest all-arithmetic-primitives-test
  (testing "All arithmetic primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (= 30 (compile-and-run (binop '+ 10 20))))
      (is (= 5 (compile-and-run (binop '- 15 10))))
      (is (= 50 (compile-and-run (binop '* 5 10))))
      (is (= 4 (compile-and-run (binop '/ 20 5)))))))


(deftest comparison-operations-test
  (testing "Comparison primitive operations"
    (let [binop (fn [op a b]
                  {:type :application,
                   :operator {:type :variable, :name op},
                   :operands [{:type :literal, :value a}
                              {:type :literal, :value b}]})]
      (is (true? (compile-and-run (binop '= 5 5))))
      (is (false? (compile-and-run (binop '= 5 6))))
      (is (true? (compile-and-run (binop '< 3 5))))
      (is (true? (compile-and-run (binop '> 10 5)))))))


(deftest addition-edge-cases-test
  (testing "Addition edge cases"
    (let [add (fn [a b]
                {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value a}
                            {:type :literal, :value b}]})]
      (is (= 0 (compile-and-run (add 0 0))))
      (is (= 0 (compile-and-run (add -5 5))))
      (is (= -10 (compile-and-run (add -3 -7)))))))


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
      (is (= 8 (compile-and-run ast))))))


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
      (is (= 14 (compile-and-run ast))))))


;; =============================================================================
;; IVMEval protocol tests
;; =============================================================================

(deftest eval-literal-test
  (testing "vm/eval evaluates a literal"
    (let [result (vm/eval (ast-walker/create-vm) {:type :literal, :value 42})]
      (is (vm/halted? result))
      (is (= 42 (vm/value result))))))


(deftest eval-arithmetic-test
  (testing "vm/eval evaluates (+ 10 20)"
    (let [ast {:type :application,
               :operator {:type :variable, :name '+},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (vm/eval (ast-walker/create-vm) ast)]
      (is (= 30 (vm/value result))))))


(deftest eval-lambda-test
  (testing "vm/eval evaluates ((fn [x] (+ x 1)) 10)"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 10}]}
          result (vm/eval (ast-walker/create-vm) ast)]
      (is (= 11 (vm/value result))))))


;; =============================================================================
;; ast->datoms contract tests
;; =============================================================================
;; These test the CONTRACT, not implementation details:
;; - Datoms are 5-tuples [e a v t m]
;; - Entity IDs are tempids (negative integers)
;; - Attributes use :yin/ namespace
;; - Entity references correctly link parent to child nodes

(deftest ast->datoms-shape-test
  (testing "All datoms are 5-tuples [e a v t m]"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42}))]
      (is (every? #(= 5 (count %)) datoms) "Every datom must be a 5-tuple")))
  (testing "Entity IDs are tempids (negative integers)"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42}))]
      (is (every? #(neg-int? (first %)) datoms)
          "Entity ID (position 0) must be a negative integer tempid")))
  (testing "Attributes use :yin/ namespace"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42}))
          attrs (map second datoms)]
      (is (every? #(= "yin" (namespace %)) attrs)
          "All attributes must be in :yin/ namespace")))
  (testing "Transaction ID and metadata default to 0"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42}))]
      (is (every? #(= 0 (nth % 3)) datoms)
          "Transaction ID (position 3) defaults to 0")
      (is (every? #(= 0 (nth % 4)) datoms)
          "Metadata (position 4) defaults to 0 (nil metadata entity)"))))


(deftest ast->datoms-entity-references-test
  (testing "Lambda body is referenced by tempid"
    (let [datoms (vec (vm/ast->datoms {:type :lambda,
                                       :params ['x],
                                       :body {:type :variable, :name 'x}}))
          lambda-datoms (filter #(= :lambda (nth % 2)) datoms)
          body-ref-datom (first (filter #(= :yin/body (second %)) datoms))]
      (is (= 1 (count lambda-datoms)) "Should have one lambda node")
      (is (some? body-ref-datom) "Lambda should have :yin/body attribute")
      (is (neg-int? (nth body-ref-datom 2))
          ":yin/body value should be a tempid reference (negative integer)")))
  (testing "Application operands are vector of tempid references"
    (let [datoms (vec (vm/ast->datoms {:type :application,
                                       :operator {:type :variable, :name '+},
                                       :operands [{:type :literal, :value 1}
                                                  {:type :literal, :value 2}]}))
          operands-datom (first (filter #(= :yin/operands (second %)) datoms))]
      (is (some? operands-datom)
          "Application should have :yin/operands attribute")
      (is (vector? (nth operands-datom 2)) ":yin/operands should be a vector")
      (is
        (every? neg-int? (nth operands-datom 2))
        ":yin/operands should contain tempid references (negative integers)"))))


(deftest ast->datoms-options-test
  (testing "Custom transaction ID"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42} {:t 1000}))]
      (is (every? #(= 1000 (nth % 3)) datoms) "Transaction ID should be 1000")))
  (testing "Custom metadata entity"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42} {:m 5}))]
      (is (every? #(= 5 (nth % 4)) datoms) "Metadata entity should be 5"))))


(deftest datoms->tx-data-nil-value-test
  (testing
    "Transacting nil literals succeeds by omitting nil :db/add assertions"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value nil}))
          tx-data (vec (vm/datoms->tx-data datoms))
          conn (d/conn-from-db (d/empty-db vm/schema))
          _ (d/transact! conn tx-data)
          db @conn]
      (is (some #(= :yin/type (nth % 2)) tx-data)
          "Type assertion should still be projected to tx-data")
      (is (not-any? #(and (= :yin/value (nth % 2)) (nil? (nth % 3))) tx-data)
          "Nil-valued assertions should be omitted from tx-data")
      (is (some? db) "Transaction should succeed and return a db"))))


(deftest ast->datoms-fibonacci-test
  (testing "Fibonacci lambda produces valid datoms"
    (let [fib-ast
          {:type :lambda,
           :params ['n],
           :body {:type :if,
                  :test {:type :application,
                         :operator {:type :variable, :name '<},
                         :operands [{:type :variable, :name 'n}
                                    {:type :literal, :value 2}]},
                  :consequent {:type :variable, :name 'n},
                  :alternate
                  {:type :application,
                   :operator {:type :variable, :name '+},
                   :operands
                   [{:type :application,
                     :operator {:type :variable, :name 'fib},
                     :operands [{:type :application,
                                 :operator {:type :variable, :name '-},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal, :value 1}]}]}
                    {:type :application,
                     :operator {:type :variable, :name 'fib},
                     :operands [{:type :application,
                                 :operator {:type :variable, :name '-},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal,
                                             :value 2}]}]}]}}}
          datoms (vec (vm/ast->datoms fib-ast))]
      (testing "All datoms are valid 5-tuples"
        (is (every? #(= 5 (count %)) datoms) "Every datom must be a 5-tuple")
        (is (every? #(neg-int? (first %)) datoms)
            "All entity IDs must be negative integer tempids")
        (is (every? #(= "yin" (namespace (second %))) datoms)
            "All attributes must be in :yin/ namespace"))
      (testing "Contains expected node types"
        (let [types (->> datoms
                         (filter #(= :yin/type (second %)))
                         (map #(nth % 2))
                         frequencies)]
          (is (= 1 (get types :lambda)) "Should have 1 lambda")
          (is (= 1 (get types :if)) "Should have 1 if")
          (is (= 6 (get types :application))
              "Should have 6 applications: <, +, fib, -, fib, -")
          (is (= 10 (get types :variable))
              "Should have 10 variables: <, n, n, +, fib, -, n, fib, -, n")
          (is (= 3 (get types :literal)) "Should have 3 literals: 2, 1, 2")))
      (testing "Entity references form valid graph"
        (let [entity-ids (set (map first datoms))
              refs (->> datoms
                        (filter #(#{:yin/body :yin/operator :yin/test
                                    :yin/consequent :yin/alternate}
                                  (second %)))
                        (map #(nth % 2)))]
          (is (every? #(contains? entity-ids %) refs)
              "All entity references should point to existing entities"))))))


;; =============================================================================
;; Stream operation tests (AST walker specific)
;; =============================================================================

(deftest stream-make-test
  (testing "stream/make creates a stream reference"
    (let [vm (-> (ast-walker/create-vm)
                 (vm/load-program {:type :stream/make, :buffer 10})
                 (vm/run))]
      (is (= :stream-ref (:type (vm/value vm))))
      (is (keyword? (:id (vm/value vm))))))
  (testing "stream/make with default buffer"
    (let [vm (-> (ast-walker/create-vm)
                 (vm/load-program {:type :stream/make})
                 (vm/run))
          stream-id (:id (vm/value vm))
          stream (get (vm/store vm) stream-id)]
      (is (= :stream (:type stream)))
      (is (= 1024 (:capacity stream)))
      (is (= [] (:buffer stream))))))


(deftest stream-put-test
  (testing "stream/put adds value to stream buffer"
    (let [vm-with-stream (-> (ast-walker/create-vm)
                             (vm/load-program {:type :stream/make, :buffer 5})
                             (vm/run))
          stream-ref (vm/value vm-with-stream)
          stream-id (:id stream-ref)
          vm-after-put (-> vm-with-stream
                           (vm/load-program {:type :stream/put,
                                             :target {:type :literal,
                                                      :value stream-ref},
                                             :val {:type :literal, :value 42}})
                           (vm/run))
          stream (get (vm/store vm-after-put) stream-id)]
      (is (= 42 (vm/value vm-after-put)))
      (is (= [42] (:buffer stream)))))
  (testing "stream/put multiple values"
    (let [vm-with-stream (-> (ast-walker/create-vm)
                             (vm/load-program {:type :stream/make, :buffer 10})
                             (vm/run))
          stream-ref (vm/value vm-with-stream)
          stream-id (:id stream-ref)
          put-ast (fn [val]
                    {:type :stream/put,
                     :target {:type :literal, :value stream-ref},
                     :val {:type :literal, :value val}})
          vm-after-puts (-> vm-with-stream
                            (vm/load-program (put-ast 1))
                            (vm/run)
                            (vm/load-program (put-ast 2))
                            (vm/run))
          stream (get (vm/store vm-after-puts) stream-id)]
      (is (= [1 2] (:buffer stream))))))


(deftest stream-take-test
  (testing "stream/take retrieves value from stream buffer"
    (let [vm-with-stream (-> (ast-walker/create-vm)
                             (vm/load-program {:type :stream/make, :buffer 5})
                             (vm/run))
          stream-ref (vm/value vm-with-stream)
          stream-id (:id stream-ref)
          vm-after-put (-> vm-with-stream
                           (vm/load-program {:type :stream/put,
                                             :target {:type :literal,
                                                      :value stream-ref},
                                             :val {:type :literal, :value 99}})
                           (vm/run))
          vm-after-take (-> vm-after-put
                            (vm/load-program {:type :stream/take,
                                              :source {:type :literal,
                                                       :value stream-ref}})
                            (vm/run))
          stream (get (vm/store vm-after-take) stream-id)]
      (is (= 99 (vm/value vm-after-take)))
      (is (= [] (:buffer stream)))))
  (testing "stream/take from empty stream blocks"
    (let [vm-with-stream (-> (ast-walker/create-vm)
                             (vm/load-program {:type :stream/make, :buffer 5})
                             (vm/run))
          stream-ref (vm/value vm-with-stream)
          vm-after-take (-> vm-with-stream
                            (vm/load-program {:type :stream/take,
                                              :source {:type :literal,
                                                       :value stream-ref}})
                            (vm/run))]
      (is (= :yin/blocked (vm/value vm-after-take))))))


(deftest stream-fifo-ordering-test
  (testing "stream maintains FIFO order"
    (let [vm-with-stream (-> (ast-walker/create-vm)
                             (vm/load-program {:type :stream/make, :buffer 10})
                             (vm/run))
          stream-ref (vm/value vm-with-stream)
          put-ast (fn [val]
                    {:type :stream/put,
                     :target {:type :literal, :value stream-ref},
                     :val {:type :literal, :value val}})
          take-ast {:type :stream/take,
                    :source {:type :literal, :value stream-ref}}
          vm-after-puts (-> vm-with-stream
                            (vm/load-program (put-ast :first))
                            (vm/run)
                            (vm/load-program (put-ast :second))
                            (vm/run)
                            (vm/load-program (put-ast :third))
                            (vm/run))
          vm-take1 (-> vm-after-puts
                       (vm/load-program take-ast)
                       (vm/run))
          vm-take2 (-> vm-take1
                       (vm/load-program take-ast)
                       (vm/run))
          vm-take3 (-> vm-take2
                       (vm/load-program take-ast)
                       (vm/run))]
      (is (= :first (vm/value vm-take1)))
      (is (= :second (vm/value vm-take2)))
      (is (= :third (vm/value vm-take3))))))


(deftest stream-with-lambda-test
  (testing "stream operations within lambda application"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :stream/put,
                                 :target {:type :variable, :name 's},
                                 :val {:type :literal, :value 42}}},
               :operands [{:type :stream/make, :buffer 5}]}]
      (is (= 42 (compile-and-run ast))))))


(deftest stream-put-take-roundtrip-test
  (testing "put then take roundtrip within nested lambdas"
    (let [ast {:type :application,
               :operator {:type :lambda,
                          :params ['s],
                          :body {:type :application,
                                 :operator {:type :lambda,
                                            :params ['_],
                                            :body {:type :stream/take,
                                                   :source {:type :variable,
                                                            :name 's}}},
                                 :operands
                                 [{:type :stream/put,
                                   :target {:type :variable, :name 's},
                                   :val {:type :literal, :value 42}}]}},
               :operands [{:type :stream/make, :buffer 5}]}]
      (is (= 42 (compile-and-run ast))))))
