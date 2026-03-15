(ns yin.vm.wasm-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.clojure :as yang]
    [yin.vm :as vm]
    [yin.vm.wasm :as wasm]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- app
  "Build an :application AST node."
  [op & operands]
  {:type :application
   :operator {:type :variable :name op}
   :operands (vec operands)})


(defn- lit
  [v]
  {:type :literal :value v})


;; =============================================================================
;; create-vm (platform-agnostic)
;; =============================================================================

(deftest create-vm-test
  (testing "creates WasmVM with expected initial state"
    (let [vm (wasm/create-vm)]
      (is (not (vm/halted? vm)))
      (is (nil? (vm/value vm)))
      (is (not (vm/blocked? vm)))
      (is (contains? (vm/store vm) vm/ffi-out-stream-key)))))


;; =============================================================================
;; WASM compilation pipeline (platform-agnostic)
;; =============================================================================

(deftest ast-datoms->asm-test
  (testing "literal number produces f64.const instruction"
    (let [datoms (vm/ast->datoms (lit 42))
          ir     (wasm/ast-datoms->asm datoms)]
      (is (= :f64 (:type ir)))
      (is (= [[:f64.const 42.0]] (:instrs ir)))))

  (testing "boolean true produces i32.const 1"
    (let [datoms (vm/ast->datoms (lit true))
          ir     (wasm/ast-datoms->asm datoms)]
      (is (= :i32 (:type ir)))
      (is (= [[:i32.const 1]] (:instrs ir)))))

  (testing "boolean false produces i32.const 0"
    (let [datoms (vm/ast->datoms (lit false))
          ir     (wasm/ast-datoms->asm datoms)]
      (is (= :i32 (:type ir)))
      (is (= [[:i32.const 0]] (:instrs ir)))))

  (testing "(+ 3 4) produces f64 add sequence"
    (let [ir (wasm/ast-datoms->asm (vm/ast->datoms (app '+ (lit 3) (lit 4))))]
      (is (= :f64 (:type ir)))
      (is (= [[:f64.const 3.0] [:f64.const 4.0] [:f64.add]] (:instrs ir)))))

  (testing "(= 1 1) produces i32 result"
    (let [ir (wasm/ast-datoms->asm (vm/ast->datoms (app '= (lit 1) (lit 1))))]
      (is (= :i32 (:type ir)))))

  (testing "(if ...) produces if/else/end structure"
    (let [ast {:type :if
               :test (app '< (lit 1) (lit 2))
               :consequent (lit 100)
               :alternate  (lit 200)}
          ir  (wasm/ast-datoms->asm (vm/ast->datoms ast))]
      (is (= :f64 (:type ir)))
      (is (some #(= [:if :f64] %) (:instrs ir)))
      (is (some #(= [:else] %) (:instrs ir)))
      (is (some #(= [:end] %) (:instrs ir))))))


(deftest assemble-test
  (testing "assemble returns a byte vector starting with WASM magic"
    (let [ir    (wasm/ast-datoms->asm (vm/ast->datoms (lit 42)))
          bytes (wasm/assemble ir)]
      (is (vector? bytes))
      (is (= [0x00 0x61 0x73 0x6D] (take 4 bytes)))
      (is (= [0x01 0x00 0x00 0x00] (take 4 (drop 4 bytes))))))

  (testing "f64 result module uses 0x7C type byte"
    (let [ir    {:type :f64 :instrs [[:f64.const 1.0]]}
          bytes (wasm/assemble ir)]
      ;; type section payload ends with result type byte
      (is (some #(= 0x7C %) bytes))))

  (testing "i32 result module uses 0x7F type byte"
    (let [ir    {:type :i32 :instrs [[:i32.const 1]]}
          bytes (wasm/assemble ir)]
      (is (some #(= 0x7F %) bytes)))))


;; =============================================================================
;; Program extraction and multi-function IR (platform-agnostic)
;; =============================================================================

(deftest extract-program-test
  (testing "single literal returns empty fns"
    (let [{:keys [fns main]} (wasm/extract-program {:type :literal, :value 42})]
      (is (empty? fns))
      (is (= {:type :literal, :value 42} main))))

  (testing "extracts one def and main"
    (let [lambda {:type :lambda, :params ['n], :body {:type :literal, :value 0}}
          ast    {:type :application
                  :operator {:type :lambda
                             :params ['_]
                             :body {:type :application
                                    :operator {:type :variable, :name 'fib}
                                    :operands [{:type :literal, :value 7}]}}
                  :operands [{:type :application
                              :operator {:type :variable, :name 'yin/def}
                              :operands [{:type :literal, :value 'fib}
                                         lambda]}]}
          {:keys [fns main]} (wasm/extract-program ast)]
      (is (= 1 (count fns)))
      (is (= 'fib (:name (first fns))))
      (is (= ['n] (:params (first fns))))
      (is (= {:type :application
              :operator {:type :variable, :name 'fib}
              :operands [{:type :literal, :value 7}]}
             main)))))


(deftest program->asm-fib-test
  (testing "fib IR contains local.get and call"
    (let [fib-ast (yang/compile-program
                    '[(def fib
                        (fn [n]
                          (if (< n 2)
                            n
                            (+ (fib (- n 1))
                               (fib (- n 2))))))
                      (fib 7)])
          {:keys [fns main]} (wasm/program->asm (wasm/extract-program fib-ast))]
      (is (= 1 (count fns)))
      (is (some #(= [:local.get 0] %) (:instrs (first fns))))
      (is (some #(= [:call 0] %) (:instrs (first fns))))
      (is (some #(= [:call 0] %) (:instrs main))))))


(deftest program->asm-defn-test
  (testing "compile-program defn output stays compatible with wasm extraction"
    (let [ast (yang/compile-program
                '[(defn double
                    [x]
                    (* x 2))
                  (double 3)])
          {:keys [fns main]} (wasm/extract-program ast)
          asm (wasm/program->asm {:fns fns :main main})]
      (is (= 1 (count fns)))
      (is (= 'double (:name (first fns))))
      (is (some #(= [:call 0] %) (:instrs (:main asm)))))))


;; =============================================================================
;; End-to-end eval (CLJS only — requires WebAssembly runtime)
;; =============================================================================

#?(:cljs
   (do
     (deftest eval-literal-test
       (testing "numeric literal evaluates to f64"
         (let [result (vm/eval (wasm/create-vm) (lit 42))]
           (is (vm/halted? result))
           (is (= 42.0 (vm/value result)))))

       (testing "boolean true evaluates to Clojure boolean true"
         (let [result (vm/eval (wasm/create-vm) (lit true))]
           (is (true? (vm/value result)))))

       (testing "boolean false evaluates to Clojure boolean false"
         (let [result (vm/eval (wasm/create-vm) (lit false))]
           (is (false? (vm/value result))))))


     (deftest eval-arithmetic-test
       (testing "(+ 3 4) -> 7.0"
         (is (= 7.0 (vm/value (vm/eval (wasm/create-vm) (app '+ (lit 3) (lit 4)))))))

       (testing "(- 10 3) -> 7.0"
         (is (= 7.0 (vm/value (vm/eval (wasm/create-vm) (app '- (lit 10) (lit 3)))))))

       (testing "(* 3 4) -> 12.0"
         (is (= 12.0 (vm/value (vm/eval (wasm/create-vm) (app '* (lit 3) (lit 4)))))))

       (testing "(/ 10 4) -> 2.5"
         (is (= 2.5 (vm/value (vm/eval (wasm/create-vm) (app '/ (lit 10) (lit 4)))))))

       (testing "(* (+ 1 2) (- 5 3)) -> 6.0"
         (let [ast (app '* (app '+ (lit 1) (lit 2))
                        (app '- (lit 5) (lit 3)))]
           (is (= 6.0 (vm/value (vm/eval (wasm/create-vm) ast)))))))


     (deftest eval-comparison-test
       (testing "(= 1 1) -> true"
         (is (true? (vm/value (vm/eval (wasm/create-vm) (app '= (lit 1) (lit 1)))))))

       (testing "(= 1 2) -> false"
         (is (false? (vm/value (vm/eval (wasm/create-vm) (app '= (lit 1) (lit 2)))))))

       (testing "(< 1 2) -> true"
         (is (true? (vm/value (vm/eval (wasm/create-vm) (app '< (lit 1) (lit 2)))))))

       (testing "(< 2 1) -> false"
         (is (false? (vm/value (vm/eval (wasm/create-vm) (app '< (lit 2) (lit 1)))))))

       (testing "(> 2 1) -> true"
         (is (true? (vm/value (vm/eval (wasm/create-vm) (app '> (lit 2) (lit 1)))))))

       (testing "(<= 2 2) -> true"
         (is (true? (vm/value (vm/eval (wasm/create-vm) (app '<= (lit 2) (lit 2)))))))

       (testing "(>= 3 2) -> true"
         (is (true? (vm/value (vm/eval (wasm/create-vm) (app '>= (lit 3) (lit 2))))))))


     (deftest eval-logical-test
       (testing "not literal: (not true) -> false"
         (is (false? (vm/value (vm/eval (wasm/create-vm) (app 'not (lit true)))))))

       (testing "not truthy: (not 42.0) -> false"
         (is (false? (vm/value (vm/eval (wasm/create-vm) (app 'not (lit 42.0))))))))


     (deftest eval-if-test
       (testing "(if (< 1 2) 100 200) -> 100.0"
         (let [ast {:type :if
                    :test       (app '< (lit 1) (lit 2))
                    :consequent (lit 100)
                    :alternate  (lit 200)}]
           (is (= 100.0 (vm/value (vm/eval (wasm/create-vm) ast))))))

       (testing "(if (< 2 1) 100 200) -> 200.0"
         (let [ast {:type :if
                    :test       (app '< (lit 2) (lit 1))
                    :consequent (lit 100)
                    :alternate  (lit 200)}]
           (is (= 200.0 (vm/value (vm/eval (wasm/create-vm) ast))))))

       (testing "(if (= 5 5) (* 2 3) 0) -> 6.0"
         (let [ast {:type :if
                    :test       (app '= (lit 5) (lit 5))
                    :consequent (app '* (lit 2) (lit 3))
                    :alternate  (lit 0)}]
           (is (= 6.0 (vm/value (vm/eval (wasm/create-vm) ast))))))

       (testing "truthiness: (if 0.0 100 200) -> 100.0"
         (let [ast {:type :if
                    :test (lit 0.0)
                    :consequent (lit 100)
                    :alternate (lit 200)}]
           (is (= 100.0 (vm/value (vm/eval (wasm/create-vm) ast))))))

       (testing "mixed branches promotion: (if true 42.0 false) -> 42.0"
         (let [ast {:type :if
                    :test (lit true)
                    :consequent (lit 42.0)
                    :alternate (lit false)}]
           (is (= 42.0 (vm/value (vm/eval (wasm/create-vm) ast))))))

       (testing "mixed branches promotion: (if false 42.0 true) -> 1.0 (promoted boolean)"
         (let [ast {:type :if
                    :test (lit false)
                    :consequent (lit 42.0)
                    :alternate (lit true)}]
           (is (= 1.0 (vm/value (vm/eval (wasm/create-vm) ast)))))))


     (deftest eval-fibonacci-test
       (testing "(fib 7) -> 13.0"
         (let [ast    (yang/compile-program
                        '[(def fib
                            (fn [n]
                              (if (< n 2)
                                n
                                (+ (fib (- n 1))
                                   (fib (- n 2))))))
                          (fib 7)])
               result (vm/eval (wasm/create-vm) ast)]
           (is (vm/halted? result))
           (is (= 13.0 (vm/value result))))))


     (deftest eval-factorial-test
       (testing "(fact 5) -> 120.0"
         (let [ast    (yang/compile-program
                        '[(def fact
                            (fn [n]
                              (if (= n 0)
                                1
                                (* n (fact (- n 1))))))
                          (fact 5)])
               result (vm/eval (wasm/create-vm) ast)]
           (is (vm/halted? result))
           (is (= 120.0 (vm/value result))))))))
