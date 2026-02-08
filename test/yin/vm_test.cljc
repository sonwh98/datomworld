(ns yin.vm-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.register :as register]))


(deftest test-literal-evaluation
  (testing "Integer literal evaluation"
    (let [ast {:type :literal, :value 42}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 42 (:value result)) "Integer literal should evaluate to itself")))
  (testing "String literal evaluation"
    (let [ast {:type :literal, :value "hello"}
          result (walker/run (walker/make-state {}) ast)]
      (is (= "hello" (:value result))
          "String literal should evaluate to itself"))))


(deftest test-variable-lookup
  (testing "Single variable lookup in environment"
    (let [ast {:type :variable, :name 'x}
          result (walker/run (walker/make-state {'x 100}) ast)]
      (is (= 100 (:value result)) "Should lookup variable x in environment")))
  (testing "Multiple variables in environment"
    (let [ast {:type :variable, :name 'z}
          result (walker/run (walker/make-state {'x 10, 'y 20, 'z 30}) ast)]
      (is (= 30 (:value result))
          "Should lookup variable z from environment with multiple bindings"))))


(deftest test-lambda-closure
  (testing "Lambda creates a closure"
    (let [ast {:type :lambda, :params ['x], :body {:type :variable, :name 'x}}
          result (walker/run (walker/make-state {'y 50}) ast)]
      (is (= :closure (:type (:value result))) "Lambda should create a closure")
      (is (= ['x] (:params (:value result)))
          "Closure should have correct parameters"))))


(deftest test-conditional-evaluation
  (testing "Conditional initial evaluation step"
    (let [ast {:type :if,
               :test {:type :literal, :value true},
               :consequent {:type :literal, :value "yes"},
               :alternate {:type :literal, :value "no"}}
          result (walker/eval (walker/make-state {}) ast)]
      (is (= {:type :literal, :value true} (:control result))
          "First step should evaluate the test expression"))))


(deftest test-primitive-addition
  (testing "Direct primitive addition"
    (let [add-fn (get vm/primitives '+)
          ast {:type :application,
               :operator {:type :literal, :value add-fn},
               :operands [{:type :literal, :value 10}
                          {:type :literal, :value 20}]}
          result (walker/run (walker/make-state {}) ast)]
      (is (= 30 (:value result)) "Direct call to + primitive: 10 + 20 = 30"))))


(deftest test-lambda-application
  (testing "Lambda that adds two numbers"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x y) (+ x y)) 3 5)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x 'y],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :variable, :name 'y}]}},
               :operands [{:type :literal, :value 3}
                          {:type :literal, :value 5}]}
          result (walker/run (walker/make-state {'+ add-fn}) ast)]
      (is (= 8 (:value result))
          "Lambda application: ((lambda (x y) (+ x y)) 3 5) = 8"))))


(deftest test-continuation-stepping
  (testing "Continuation-based evaluation with step counting"
    (let [add-fn (get vm/primitives '+)
          ;; AST: ((lambda (x) (+ x 1)) 5)
          ast {:type :application,
               :operator {:type :lambda,
                          :params ['x],
                          :body {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :variable, :name 'x}
                                            {:type :literal, :value 1}]}},
               :operands [{:type :literal, :value 5}]}
          initial-state (assoc (walker/make-state {'+ add-fn}) :control ast)]
      (testing "Multiple evaluation steps occur"
        (let [step-count (loop [state initial-state
                                steps 0]
                           (if (or (:control state) (:continuation state))
                             (recur (walker/eval state nil) (inc steps))
                             steps))]
          (is (> step-count 5)
              "Should take multiple steps to evaluate lambda application")))
      (testing "Final result is correct"
        (let [result (walker/run (walker/make-state {'+ add-fn}) ast)]
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


(deftest test-ast->datoms-entity-references
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


(deftest test-ast->datoms-options
  (testing "Custom transaction ID"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42} {:t 1000}))]
      (is (every? #(= 1000 (nth % 3)) datoms) "Transaction ID should be 1000")))
  (testing "Custom metadata entity"
    (let [datoms (vec (vm/ast->datoms {:type :literal, :value 42} {:m 5}))]
      (is (every? #(= 5 (nth % 4)) datoms) "Metadata entity should be 5"))))


(deftest test-ast->datoms-fibonacci
  (testing "Fibonacci lambda produces valid datoms"
    ;; (lambda (n)
    ;;   (if (< n 2)
    ;;     n
    ;;     (+ (fib (- n 1)) (fib (- n 2)))))
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
;; ast-datoms->asm tests
;; =============================================================================
;; These test the compilation pipeline: AST -> datoms -> register bytecode
;; and end-to-end execution via rbc-run.

(deftest test-register-bytecode-shape
  (testing "Literal produces loadk + return"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :literal,
                                                        :value 42}))]
      (is (vector? bc))
      (is (= 2 (count bc)))
      (is (= :loadk (first (first bc))) "First instruction should be :loadk")
      (is (= 42 (nth (first bc) 2)) "Should load the value 42")
      (is (= :return (first (last bc))) "Last instruction should be :return")))
  (testing "Variable produces loadv + return"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :variable,
                                                        :name 'x}))]
      (is (= 2 (count bc)))
      (is (= :loadv (first (first bc))))
      (is (= 'x (nth (first bc) 2)))
      (is (= :return (first (last bc))))))
  (testing "All instructions are vectors"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :variable, :name '+},
                                :operands [{:type :literal, :value 1}
                                           {:type :literal, :value 2}]}))]
      (is (every? vector? bc)))))


(deftest test-register-bytecode-application
  (testing "Application produces loadk, loadv, call, and return"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :variable, :name '+},
                                :operands [{:type :literal, :value 1}
                                           {:type :literal, :value 2}]}))]
      ;; Should have: loadk, loadk, loadv, call, return
      (is (= 5 (count bc)))
      (is (= :loadk (first (nth bc 0))))
      (is (= :loadk (first (nth bc 1))))
      (is (= :loadv (first (nth bc 2))))
      (is (= :call (first (nth bc 3))))
      (is (= :return (first (nth bc 4))))))
  (testing "Call instruction references correct registers"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :variable, :name '+},
                                :operands [{:type :literal, :value 1}
                                           {:type :literal, :value 2}]}))
          call-instr (nth bc 3)
          [op rd rf arg-regs] call-instr]
      (is (= :call op))
      (is (integer? rd) "Result register should be an integer")
      (is (integer? rf) "Function register should be an integer")
      (is (vector? arg-regs) "Arg registers should be a vector")
      (is (= 2 (count arg-regs)) "Should have 2 arg registers"))))


(deftest test-register-bytecode-lambda
  (testing "Lambda produces closure, jump, body, and return"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :lambda,
                                                        :params ['x],
                                                        :body {:type :variable,
                                                               :name 'x}}))]
      (is (= :closure (first (nth bc 0)))
          "First instruction should be :closure")
      (is (= :jump (first (nth bc 1)))
          "Second instruction should be :jump over body")
      (is (= :loadv (first (nth bc 2))) "Body should start with :loadv")
      (is (= :return (first (nth bc 3))) "Body should end with :return")))
  (testing "Closure body address points to correct instruction"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :lambda,
                                                        :params ['x],
                                                        :body {:type :variable,
                                                               :name 'x}}))
          [_ _rd _params body-addr] (first bc)]
      (is (= 2 body-addr)
          "Body should start at instruction 2 (after closure + jump)")))
  (testing "Jump skips over body to return"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :lambda,
                                                        :params ['x],
                                                        :body {:type :variable,
                                                               :name 'x}}))
          [_ jump-addr] (nth bc 1)
          ;; Jump should land on the :return that follows the closure body
          jump-target (get bc jump-addr)]
      (is (= :return (first jump-target))
          "Jump should land on the final :return"))))


(deftest test-register-bytecode-conditional
  (testing "If produces branch, both branches, and move instructions"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :if,
                                :test {:type :literal, :value true},
                                :consequent {:type :literal, :value 1},
                                :alternate {:type :literal, :value 0}}))]
      (is (= :loadk (first (first bc))) "Should start with test expression")
      (is (some #(= :branch (first %)) bc)
          "Should contain a branch instruction")
      (is (some #(= :move (first %)) bc)
          "Should contain move instructions for result register"))))


;; =============================================================================
;; End-to-end: AST -> datoms -> register bytecode -> rbc-run
;; =============================================================================

(deftest test-register-bytecode-execution-literal
  (testing "Literal through full pipeline"
    (let [bc (register/ast-datoms->asm (vm/ast->datoms {:type :literal,
                                                        :value 42}))
          result (register/rbc-run (register/make-rbc-state bc))]
      (is (= 42 (:value result))))))


(deftest test-register-bytecode-execution-addition
  (testing "(+ 1 2) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :variable, :name '+},
                                :operands [{:type :literal, :value 1}
                                           {:type :literal, :value 2}]}))
          result (register/rbc-run (register/make-rbc-state bc {'+ +}))]
      (is (= 3 (register/rbc-get-reg result 3)) "(+ 1 2) should produce 3"))))


(deftest test-register-bytecode-execution-closure
  (testing "((fn [x] x) 42) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :lambda,
                                           :params ['x],
                                           :body {:type :variable, :name 'x}},
                                :operands [{:type :literal, :value 42}]}))
          result (register/rbc-run (register/make-rbc-state bc))]
      (is (= 42 (:value result))
          "Identity closure should return its argument")))
  (testing "((fn [x] (+ x 1)) 5) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms
                 {:type :application,
                  :operator {:type :lambda,
                             :params ['x],
                             :body {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :variable, :name 'x}
                                               {:type :literal, :value 1}]}},
                  :operands [{:type :literal, :value 5}]}))
          result (register/rbc-run (register/make-rbc-state bc {'+ +}))]
      (is (= 6 (:value result)) "((fn [x] (+ x 1)) 5) should produce 6"))))


(deftest test-register-bytecode-execution-conditional
  (testing "(if true 1 0) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :if,
                                :test {:type :literal, :value true},
                                :consequent {:type :literal, :value 1},
                                :alternate {:type :literal, :value 0}}))
          result (register/rbc-run (register/make-rbc-state bc))]
      (is (= 1 (:value result)) "True branch should be taken")))
  (testing "(if false 1 0) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :if,
                                :test {:type :literal, :value false},
                                :consequent {:type :literal, :value 1},
                                :alternate {:type :literal, :value 0}}))
          result (register/rbc-run (register/make-rbc-state bc))]
      (is (= 0 (:value result)) "False branch should be taken"))))


(deftest test-register-bytecode-execution-nested
  (testing "((fn [x y] (+ x y)) 3 5) through full pipeline"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms
                 {:type :application,
                  :operator {:type :lambda,
                             :params ['x 'y],
                             :body {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :variable, :name 'x}
                                               {:type :variable, :name 'y}]}},
                  :operands [{:type :literal, :value 3}
                             {:type :literal, :value 5}]}))
          result (register/rbc-run (register/make-rbc-state bc {'+ +}))]
      (is (= 8 (:value result)) "((fn [x y] (+ x y)) 3 5) should produce 8"))))


(deftest test-register-bytecode-continuation-is-data
  (testing "Continuation frame is created during closure call"
    (let [bc (register/ast-datoms->asm
               (vm/ast->datoms {:type :application,
                                :operator {:type :lambda,
                                           :params ['x],
                                           :body {:type :variable, :name 'x}},
                                :operands [{:type :literal, :value 42}]}))
          ;; Step until we're inside the closure body
          states
            (loop [s (register/make-rbc-state bc)
                   acc []]
              (if (:halted s) acc (recur (register/rbc-step s) (conj acc s))))
          ;; Find state where :k is non-nil (inside closure)
          inside-closure (first (filter :k states))]
      (is (some? inside-closure)
          "Should have a state with non-nil continuation")
      (is (= :call-frame (:type (:k inside-closure)))
          "Continuation should be a :call-frame")
      (is (vector? (:saved-regs (:k inside-closure)))
          "Continuation should save caller registers")
      (is (integer? (:return-ip (:k inside-closure)))
          "Continuation should have a return IP"))))


(comment
  ;; REPL usage examples: Run all tests
  #?(:clj (clojure.test/run-tests 'yin.vm-test)
     :cljs (cljs.test/run-tests 'yin.vm-test))
  ;; Run individual tests
  (test-literal-evaluation)
  (test-variable-lookup)
  (test-lambda-closure)
  (test-primitive-addition)
  (test-lambda-application)
  (test-continuation-stepping)
  ;; Manual REPL exploration: Step through evaluation manually
  (def my-state
    {:control {:type :literal, :value 42},
     :environment {},
     :store {},
     :continuation nil,
     :value nil})
  (walker/eval my-state nil)
  ;; Run to completion
  (walker/run (walker/make-state {}) {:type :literal, :value 99})
  ;; Test lambda addition manually
  (def add-fn (get vm/primitives '+))
  (def add-lambda-ast
    {:type :application,
     :operator {:type :lambda,
                :params ['x 'y],
                :body {:type :application,
                       :operator {:type :variable, :name '+},
                       :operands [{:type :variable, :name 'x}
                                  {:type :variable, :name 'y}]}},
     :operands [{:type :literal, :value 3} {:type :literal, :value 5}]})
  (walker/run (walker/make-state {'+ add-fn}) add-lambda-ast))
