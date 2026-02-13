(ns yang.php-test
  (:require [clojure.test :refer :all]
            [yang.clojure :as clj]
            [yang.php :as php]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]))


(defn compile-and-run
  ([ast] (compile-and-run ast {}))
  ([ast env]
   (-> (ast-walker/create-vm {:env (merge vm/primitives env)})
       (vm/load-program ast)
       (vm/run)
       (vm/value))))


(deftest test-tokenize
  (testing "Tokenizing PHP source"
    (is (= [[:number "42"]] (php/tokenize "42")))
    (is (= [[:string "hello"]] (php/tokenize "\"hello\"")))
    (is (= [[:variable "$x"] [:operator "+"] [:number "1"]]
           (php/tokenize "$x + 1")))
    (is (= [[:keyword "function"] [:identifier "add"] [:lparen "("]
            [:variable "$x"] [:comma ","] [:variable "$y"] [:rparen ")"]
            [:lbrace "{"] [:keyword "return"] [:variable "$x"] [:operator "+"]
            [:variable "$y"] [:semicolon ";"] [:rbrace "}"]]
           (php/tokenize "function add($x, $y) { return $x + $y; }")))))


(deftest test-compile-literals
  (testing "Compiling PHP literals"
    (testing "Numbers"
      (is (= {:type :literal, :value 42} (php/compile "42;")))
      (is (= {:type :literal, :value -10} (php/compile "-10;")))
      (is (= {:type :literal, :value 3.14} (php/compile "3.14;"))))
    (testing "Strings"
      (is (= {:type :literal, :value "hello"} (php/compile "\"hello\";")))
      (is (= {:type :literal, :value "world"} (php/compile "'world';"))))
    (testing "Booleans"
      (is (= {:type :literal, :value true} (php/compile "true;")))
      (is (= {:type :literal, :value false} (php/compile "false;"))))
    (testing "null"
      (is (= {:type :literal, :value nil} (php/compile "null;"))))))


(deftest test-compile-variables
  (testing "Compiling PHP variables"
    (is (= {:type :variable, :name 'x} (php/compile "$x;")))
    (is (= {:type :variable, :name 'foo} (php/compile "$foo;")))))


(deftest test-compile-binary-ops
  (testing "Compiling PHP binary operations"
    (testing "Addition"
      (let [ast (php/compile "1 + 2;")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '+} (:operator ast)))
        (is (= 2 (count (:operands ast))))))
    (testing "Multiplication"
      (let [ast (php/compile "$x * 2;")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '*} (:operator ast)))))
    (testing "Comparison"
      (let [ast (php/compile "$x < 5;")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name '<} (:operator ast)))))
    (testing "Operator precedence"
      (let [ast (php/compile "2 + 3 * 4;")]
        ;; Should be: 2 + (3 * 4)
        (is (= :application (:type ast)))
        (is (= '+ (get-in ast [:operator :name])))
        (is (= :application (get-in ast [:operands 1 :type])))
        (is (= '* (get-in ast [:operands 1 :operator :name])))))))


(deftest test-compile-function
  (testing "Compiling PHP function statements"
    (testing "Simple function"
      (let [ast (php/compile "function double($x) { return $x * 2; }")]
        (is (= :application (:type ast)))))
    (testing "Function with multiple parameters"
      (let [ast (php/compile "function add($x, $y) { return $x + $y; }")]
        (is (= :application (:type ast)))))))


(deftest test-compile-call
  (testing "Compiling PHP function calls"
    (testing "Simple call"
      (let [ast (php/compile "f(5);")]
        (is (= :application (:type ast)))
        (is (= {:type :variable, :name 'f} (:operator ast)))
        (is (= 1 (count (:operands ast))))))
    (testing "Multiple arguments"
      (let [ast (php/compile "add(1, 2);")]
        (is (= :application (:type ast)))
        (is (= 2 (count (:operands ast))))))
    (testing "Nested calls"
      (let [ast (php/compile "f(g(5));")]
        (is (= :application (:type ast)))
        (is (= :application (get-in ast [:operands 0 :type])))))))


(deftest test-end-to-end-php
  (testing "Compile and execute PHP code with Yin VM"
    (testing "Simple literal" (is (= 42 (compile-and-run (php/compile "42;")))))
    (testing "Variable lookup"
      (is (= 100 (compile-and-run (php/compile "$x;") {'x 100}))))
    (testing "Simple addition"
      (is (= 3 (compile-and-run (php/compile "1 + 2;")))))
    (testing "Function application"
      (let
        [source
           "function double($x) { return $x * 2; }
                    double(21);"
         ast (php/compile source)]
        (is (= 42 (compile-and-run ast)))))
    (testing "Multiple parameters"
      (let
        [source
           "function add($x, $y) { return $x + $y; }
                    add(10, 20);"
         ast (php/compile source)]
        (is (= 30 (compile-and-run ast)))))
    (testing "Operator precedence"
      (is (= 14 (compile-and-run (php/compile "2 + 3 * 4;")))))
    (testing "Nested operations"
      (is (= 20 (compile-and-run (php/compile "(2 + 3) * 4;")))))
    (testing "Comparison"
      (is (= true (compile-and-run (php/compile "3 < 5;")))))
    (testing "If statement - true branch"
      (let [source "if (true) { return 10; } else { return 20; }"
            ast (php/compile source)]
        (is (= 10 (compile-and-run ast)))))
    (testing "If statement - false branch"
      (let [source "if (false) { return 10; } else { return 20; }"
            ast (php/compile source)]
        (is (= 20 (compile-and-run ast)))))
    (testing "If with comparison"
      (let [source "if (3 < 5) { return 100; } else { return 200; }"
            ast (php/compile source)]
        (is (= 100 (compile-and-run ast)))))
    (testing "Else if"
      (let
        [source
           "if (3 > 5) { return 1; } elseif (4 < 5) { return 2; } else { return 3; }"
         ast (php/compile source)]
        (is (= 2 (compile-and-run ast)))))
    (testing "Complex closure with type hints and assignment"
      (let
        [source
           "$makePower = function (int $exponent) {
                      return function (int $base) use ($exponent): int {
                          $result = 1;
                          $result = $result * $base;
                          return $result;
                      };
                    };
                    $pow2 = $makePower(2);
                    $pow2(3);"
         ast (php/compile source)]
        ;; This simplified version (no for loop yet in test, and result is
        ;; just result * base)
        ;; should at least compile and run.
        (is (= 3 (compile-and-run ast)))))))


(deftest test-php-to-clojure-equivalence
  (testing "PHP and Clojure compile to equivalent ASTs"
    (testing "Function calls"
      (let [php-ast (php/compile "f(1, 2);")
            clj-ast (clj/compile '(f 1 2))]
        (is (= (:type php-ast) (:type clj-ast) :application))
        (is (= (count (:operands php-ast)) (count (:operands clj-ast)) 2))))))


(deftest test-error-handling
  (testing "Error handling for invalid PHP"
    (testing "Unclosed string"
      (is (thrown? Exception (php/compile "\"unclosed;"))))
    (testing "Invalid syntax" (is (thrown? Exception (php/compile "function"))))
    (testing "Unmatched parenthesis"
      (is (thrown? Exception (php/compile "(1 + 2;"))))))
