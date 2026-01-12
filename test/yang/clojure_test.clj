(ns yang.clojure-test
  (:require [clojure.test :refer :all]
            [yang.clojure :as yang]
            [yin.vm :as vm]))

(defn make-state
  "Helper to create initial VM state."
  [env]
  {:control nil
   :environment env
   :store {}
   :continuation nil
   :value nil})

(deftest test-compile-literals
  (testing "Compiling literal values"
    (testing "Numbers"
      (is (= {:type :literal :value 42}
             (yang/compile 42)))
      (is (= {:type :literal :value 3.14}
             (yang/compile 3.14)))
      (is (= {:type :literal :value -10}
             (yang/compile -10))))

    (testing "Strings"
      (is (= {:type :literal :value "hello"}
             (yang/compile "hello"))))

    (testing "Booleans"
      (is (= {:type :literal :value true}
             (yang/compile true)))
      (is (= {:type :literal :value false}
             (yang/compile false))))

    (testing "Nil"
      (is (= {:type :literal :value nil}
             (yang/compile nil))))

    (testing "Keywords"
      (is (= {:type :literal :value :foo}
             (yang/compile :foo))))

    (testing "Vectors"
      (is (= {:type :literal :value [1 2 3]}
             (yang/compile [1 2 3]))))

    (testing "Maps"
      (is (= {:type :literal :value {:a 1 :b 2}}
             (yang/compile {:a 1 :b 2}))))))

(deftest test-compile-variables
  (testing "Compiling variable references"
    (is (= {:type :variable :name 'x}
           (yang/compile 'x)))
    (is (= {:type :variable :name 'foo}
           (yang/compile 'foo)))))

(deftest test-compile-lambda
  (testing "Compiling lambda expressions"
    (testing "Simple lambda"
      (let [ast (yang/compile '(fn [x] x))]
        (is (= :lambda (:type ast)))
        (is (= ['x] (:params ast)))
        (is (= {:type :variable :name 'x}
               (:body ast)))))

    (testing "Lambda with computation"
      (let [ast (yang/compile '(fn [x y] (+ x y)))]
        (is (= :lambda (:type ast)))
        (is (= ['x 'y] (:params ast)))
        (is (= :application (get-in ast [:body :type])))))))

(deftest test-compile-application
  (testing "Compiling function applications"
    (testing "Simple addition"
      (let [ast (yang/compile '(+ 1 2))]
        (is (= :application (:type ast)))
        (is (= {:type :variable :name '+}
               (:operator ast)))
        (is (= [{:type :literal :value 1}
                {:type :literal :value 2}]
               (:operands ast)))))

    (testing "Nested application"
      (let [ast (yang/compile '(+ (* 2 3) 4))]
        (is (= :application (:type ast)))
        (is (= :application (get-in ast [:operands 0 :type])))))))

(deftest test-compile-if
  (testing "Compiling conditional expressions"
    (testing "If with alternate"
      (let [ast (yang/compile '(if true 1 2))]
        (is (= :if (:type ast)))
        (is (= {:type :literal :value true}
               (:test ast)))
        (is (= {:type :literal :value 1}
               (:consequent ast)))
        (is (= {:type :literal :value 2}
               (:alternate ast)))))

    (testing "If without alternate"
      (let [ast (yang/compile '(if true 1))]
        (is (= :if (:type ast)))
        (is (= {:type :literal :value nil}
               (:alternate ast)))))))

(deftest test-compile-let
  (testing "Compiling let bindings"
    (testing "Single binding"
      (let [ast (yang/compile '(let [x 1] x))]
        (is (= :application (:type ast)))
        (is (= :lambda (get-in ast [:operator :type])))
        (is (= ['x] (get-in ast [:operator :params])))
        (is (= [{:type :literal :value 1}]
               (:operands ast)))))

    (testing "Multiple bindings"
      (let [ast (yang/compile '(let [x 1 y 2] (+ x y)))]
        (is (= :application (:type ast)))
        ;; Outer lambda for x
        (is (= :lambda (get-in ast [:operator :type])))
        (is (= ['x] (get-in ast [:operator :params])))
        ;; Inner lambda for y
        (is (= :application (get-in ast [:operator :body :type])))
        (is (= :lambda (get-in ast [:operator :body :operator :type])))
        (is (= ['y] (get-in ast [:operator :body :operator :params])))))))

(deftest test-compile-do
  (testing "Compiling do blocks"
    (testing "Empty do"
      (let [ast (yang/compile '(do))]
        (is (= {:type :literal :value nil} ast))))

    (testing "Single expression do"
      (let [ast (yang/compile '(do 42))]
        (is (= {:type :literal :value 42} ast))))

    (testing "Multiple expressions do"
      (let [ast (yang/compile '(do 1 2 3))]
        (is (= :application (:type ast)))
        ;; Should evaluate and discard intermediate results
        (is (= :lambda (get-in ast [:operator :type])))))))

(deftest test-compile-quote
  (testing "Compiling quoted forms"
    (is (= {:type :literal :value 'x}
           (yang/compile '(quote x))))
    (is (= {:type :literal :value '(+ 1 2)}
           (yang/compile '(quote (+ 1 2)))))))

(deftest test-end-to-end-compilation-and-execution
  (testing "Compile and execute with Yin VM"
    (testing "Simple literal"
      (let [ast (yang/compile 42)
            result (vm/run (make-state {}) ast)]
        (is (= 42 (:value result)))))

    (testing "Variable lookup"
      (let [ast (yang/compile 'x)
            result (vm/run (make-state {'x 100}) ast)]
        (is (= 100 (:value result)))))

    (testing "Simple addition"
      (let [ast (yang/compile '(+ 1 2))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 3 (:value result)))))

    (testing "Lambda application"
      (let [ast (yang/compile '((fn [x] (* x 2)) 21))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 42 (:value result)))))

    (testing "Nested application"
      (let [ast (yang/compile '(+ (* 2 3) 4))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 10 (:value result)))))

    (testing "Conditional - true branch"
      (let [ast (yang/compile '(if true 1 2))
            result (vm/run (make-state {}) ast)]
        (is (= 1 (:value result)))))

    (testing "Conditional - false branch"
      (let [ast (yang/compile '(if false 1 2))
            result (vm/run (make-state {}) ast)]
        (is (= 2 (:value result)))))

    (testing "Let binding"
      (let [ast (yang/compile '(let [x 5] (+ x 3)))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 8 (:value result)))))

    (testing "Multiple let bindings"
      (let [ast (yang/compile '(let [x 2 y 3] (* x y)))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 6 (:value result)))))

    (testing "Lambda with multiple parameters"
      (let [ast (yang/compile '((fn [x y] (+ x y)) 10 20))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 30 (:value result)))))

    (testing "Higher-order functions"
      (let [ast (yang/compile '((fn [f x] (f x)) (fn [n] (* n 2)) 21))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 42 (:value result)))))

    (testing "Nested let bindings"
      (let [ast (yang/compile '(let [x 1]
                                 (let [y 2]
                                   (+ x y))))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 3 (:value result)))))

    (testing "Complex expression"
      (let [ast (yang/compile '(let [double (fn [x] (* x 2))
                                     triple (fn [x] (* x 3))]
                                 (+ (double 5) (triple 4))))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 22 (:value result)))))))

(deftest test-compile-errors
  (testing "Error handling for invalid forms"
    (testing "Unknown form type"
      (is (thrown? Exception
                   (yang/compile (Object.)))))))

;; Stream compilation tests

(deftest test-compile-stream-make
  (testing "Compiling stream/make"
    (testing "With buffer size"
      (let [ast (yang/compile '(stream/make 10))]
        (is (= :stream/make (:type ast)))
        (is (= 10 (:buffer ast)))))

    (testing "Without buffer size (default)"
      (let [ast (yang/compile '(stream/make))]
        (is (= :stream/make (:type ast)))
        (is (= 1024 (:buffer ast)))))))

(deftest test-compile-stream-put
  (testing "Compiling stream/put"
    (let [ast (yang/compile '(stream/put s 42))]
      (is (= :stream/put (:type ast)))
      (is (= {:type :variable :name 's} (:target ast)))
      (is (= {:type :literal :value 42} (:val ast)))))

  (testing "Compiling >! alias"
    (let [ast (yang/compile '(>! s 42))]
      (is (= :stream/put (:type ast)))
      (is (= {:type :variable :name 's} (:target ast)))
      (is (= {:type :literal :value 42} (:val ast))))))

(deftest test-compile-stream-take
  (testing "Compiling stream/take"
    (let [ast (yang/compile '(stream/take s))]
      (is (= :stream/take (:type ast)))
      (is (= {:type :variable :name 's} (:source ast)))))

  (testing "Compiling <! alias"
    (let [ast (yang/compile '(<! s))]
      (is (= :stream/take (:type ast)))
      (is (= {:type :variable :name 's} (:source ast))))))

(deftest test-stream-end-to-end
  (testing "Compile and execute stream operations"
    (testing "Simple put and take"
      (let [ast (yang/compile '(let [s (stream/make 5)]
                                 (stream/put s 42)
                                 (stream/take s)))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 42 (:value result)))))

    (testing "With channel-style aliases"
      (let [ast (yang/compile '(let [s (stream/make)]
                                 (>! s 99)
                                 (<! s)))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 99 (:value result)))))

    (testing "Multiple values FIFO"
      (let [ast (yang/compile '(let [s (stream/make 10)]
                                 (>! s 1)
                                 (>! s 2)
                                 (>! s 3)
                                 (<! s)))
            result (vm/run (make-state vm/primitives) ast)]
        (is (= 1 (:value result)))))))

(deftest test-lambda-with-multi-expression-body
  (testing "Lambda with multiple expressions in body"
    (let [ast (yang/compile '(fn [x] (+ x 1) (+ x 2) (* x 3)))
          result (vm/run (make-state vm/primitives) ast)]
      ;; Should create a closure
      (is (= :closure (get-in result [:value :type]))))))

(deftest test-let-with-multi-expression-body
  (testing "Let with multiple expressions in body"
    (let [ast (yang/compile '(let [x 5] (+ x 1) (+ x 2) (* x 3)))
          result (vm/run (make-state vm/primitives) ast)]
      ;; Should evaluate to the last expression
      (is (= 15 (:value result))))))
