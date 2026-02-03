(ns yang.python-test
  (:require [clojure.test :refer :all]
            [yang.clojure :as clj]
            [yang.python :as py]
            [yin.vm :as vm]))


(defn make-state
  "Helper to create initial VM state."
  [env]
  {:control nil, :environment env, :store {}, :continuation nil, :value nil})


(deftest test-tokenize
  (testing "Tokenizing Python source"
    (is (= [[:number "42"] [:newline nil]] (py/tokenize "42")))
    (is (= [[:string "hello"] [:newline nil]] (py/tokenize "\"hello\"")))
    (is (= [[:identifier "x"] [:operator "+"] [:number "1"] [:newline nil]]
           (py/tokenize "x + 1")))
    (is (= [[:keyword "lambda"] [:identifier "x"] [:colon ":"] [:identifier "x"]
            [:operator "*"] [:number "2"] [:newline nil]]
           (py/tokenize "lambda x: x * 2")))))


(deftest test-compile-literals
  (testing "Compiling Python literals"
    (testing "Numbers"
      (is (= {:type :literal, :value 42} (py/compile "42")))
      (is (= {:type :literal, :value -10} (py/compile "-10")))
      (is (= {:type :literal, :value 3.14} (py/compile "3.14"))))
    (testing "Strings"
      (is (= {:type :literal, :value "hello"} (py/compile "\"hello\"")))
      (is (= {:type :literal, :value "world"} (py/compile "'world'"))))
    (testing "Booleans"
      (is (= {:type :literal, :value true} (py/compile "True")))
      (is (= {:type :literal, :value false} (py/compile "False"))))
    (testing "None" (is (= {:type :literal, :value nil} (py/compile "None"))))))


(deftest test-compile-variables
  (testing "Compiling Python variables"
    (is (= {:type :variable, :name 'x} (py/compile "x")))
    (is (= {:type :variable, :name 'foo} (py/compile "foo")))))


(deftest test-compile-binary-ops
  (testing "Compiling Python binary operations"
    (testing "Addition"
      (let [ast (py/compile "1 + 2")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '+} (:operator ast)))
        (is (= 2 (count (:operands ast))))))
    (testing "Multiplication"
      (let [ast (py/compile "x * 2")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '*} (:operator ast)))))
    (testing "Comparison"
      (let [ast (py/compile "x < 5")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '<} (:operator ast)))))
    (testing "Operator precedence"
      (let [ast (py/compile "2 + 3 * 4")]
        ;; Should be: 2 + (3 * 4)
        (is (= :application (:type ast)))
        (is (= '+ (get-in ast [:operator :name])))
        (is (= :application (get-in ast [:operands 1 :type])))
        (is (= '* (get-in ast [:operands 1 :operator :name])))))))


(deftest test-compile-lambda
  (testing "Compiling Python lambda expressions"
    (testing "Single parameter"
      (let [ast (py/compile "lambda x: x * 2")]
        (is (= :lambda (:type ast)))
        (is (= ['x] (:params ast)))
        (is (= :application (get-in ast [:body :type])))))
    (testing "Multiple parameters"
      (let [ast (py/compile "lambda x, y: x + y")]
        (is (= :lambda (:type ast)))
        (is (= ['x 'y] (:params ast)))
        (is (= :application (get-in ast [:body :type])))))))


(deftest test-compile-def
  (testing "Compiling Python def statements"
    (testing "Simple function"
      (let [ast (py/compile "def double(x): return x * 2")]
        (is (= :application (:type ast)))))
    (testing "Function with multiple parameters"
      (let [ast (py/compile "def add(x, y): return x + y")]
        (is (= :application (:type ast)))))))


(deftest test-compile-call
  (testing "Compiling Python function calls"
    (testing "Simple call"
      (let [ast (py/compile "f(5)")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name 'f} (:operator ast)))
        (is (= 1 (count (:operands ast))))))
    (testing "Multiple arguments"
      (let [ast (py/compile "add(1, 2)")]
        (is (= :application (:type ast)))
        (is (= 2 (count (:operands ast))))))
    (testing "Nested calls"
      (let [ast (py/compile "f(g(5))")]
        (is (= :application (:type ast)))
        (is (= :application (get-in ast [:operands 0 :type])))))))


(deftest test-compile-if-expr
  (testing "Compiling Python if expressions"
    (let [ast (py/compile "10 if x > 5 else 20")]
      (is (= :if (:type ast)))
      (is (= :application (get-in ast [:test :type])))
      (is (= {:type :literal, :value 10} (:consequent ast)))
      (is (= {:type :literal, :value 20} (:alternate ast))))))


(deftest test-end-to-end-python
  (testing "Compile and execute Python code with Yin VM"
    (testing "Simple literal"
      (let [ast (py/compile "42")
            result (vm/run (make-state {}) ast)]
        (is (= 42 (:value result)))))
    (testing "Variable lookup"
      (let [ast (py/compile "x")
            result (vm/run (make-state {'x 100}) ast)]
        (is (= 100 (:value result)))))
    (testing "Simple addition"
      (let [ast (py/compile "1 + 2")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 3 (:value result)))))
    (testing "Lambda application"
      (let [ast (py/compile "(lambda x: x * 2)(21)")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 42 (:value result)))))
    (testing "Def statement as lambda"
      (let [ast (py/compile "def double(x): return x * 2")
            ;; The def compiles to a lambda, we need to apply it
            app-ast {:type :application,
                     :operator ast,
                     :operands [{:type :literal, :value 21}]}
            result (vm/run (make-state vm/primitives) app-ast)]
        (is (= 42 (:value result)))))
    (testing "Multiple parameters"
      (let [ast (py/compile "(lambda x, y: x + y)(10, 20)")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 30 (:value result)))))
    (testing "Operator precedence"
      (let [ast (py/compile "2 + 3 * 4")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 14 (:value result)))))
    (testing "Nested operations"
      (let [ast (py/compile "(2 + 3) * 4")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 20 (:value result)))))
    (testing "Comparison"
      (let [ast (py/compile "3 < 5")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= true (:value result)))))
    (testing "If expression - true branch"
      (let [ast (py/compile "10 if True else 20")
            result (vm/run (make-state {}) ast)]
        (is (= 10 (:value result)))))
    (testing "If expression - false branch"
      (let [ast (py/compile "10 if False else 20")
            result (vm/run (make-state {}) ast)]
        (is (= 20 (:value result)))))
    (testing "If with comparison"
      (let [ast (py/compile "100 if 3 < 5 else 200")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 100 (:value result)))))))


(deftest test-python-to-clojure-equivalence
  (testing "Python and Clojure compile to equivalent ASTs"
    (testing "Lambda expressions"
      (let [py-ast (py/compile "lambda x: x * 2")
            clj-ast (clj/compile '(fn [x] (* x 2)))]
        (is (= (:type py-ast) (:type clj-ast) :lambda))
        (is (= (:params py-ast) (:params clj-ast)))
        ;; Bodies should be equivalent applications
        (is (= (get-in py-ast [:body :type])
               (get-in clj-ast [:body :type])
               :application))))
    (testing "Function calls"
      (let [py-ast (py/compile "f(1, 2)")
            clj-ast (clj/compile '(f 1 2))]
        (is (= (:type py-ast) (:type clj-ast) :application))
        (is (= (count (:operands py-ast)) (count (:operands clj-ast)) 2))))))


(deftest test-error-handling
  (testing "Error handling for invalid Python"
    (testing "Unclosed string"
      (is (thrown? Exception (py/compile "\"unclosed"))))
    (testing "Invalid syntax" (is (thrown? Exception (py/compile "def")))) ; Incomplete
    ;; def statement
    (testing "Unmatched parenthesis"
      (is (thrown? Exception (py/compile "(1 + 2"))))))


(deftest test-complex-python-examples
  (testing "Complex Python expressions"
    (testing "Higher-order function simulation"
      ;; Python: (lambda f: f(5))(lambda x: x * 2)
      (let [ast (py/compile "(lambda f: f(5))(lambda x: x * 2)")
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 10 (:value result)))))
    (testing "Nested if expressions"
      ;; Python: (1 if x < 5 else 2) if True else 3
      (let [ast (py/compile "(1 if x < 5 else 2) if True else 3")
            result (vm/run (make-state (merge vm/primitives {'x 3})) ast)]
        (is (= 1 (:value result)))))
    (testing "Multiple operations"
      (let [ast (py/compile "10 + 20 * 3 - 5")
            result (vm/run (make-state vm/primitives) ast)]
        ;; 10 + (20 * 3) - 5 = 10 + 60 - 5 = 65
        (is (= 65 (:value result)))))))
