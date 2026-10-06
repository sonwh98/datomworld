(ns yang.python.antlr.int-ops-test
  "C3 S3-A/B/C exact operators and range/index audit on all four VMs."
  (:require
    [clojure.test :refer [deftest is]]
    [clojure.walk :as walk]
    [dao.test-slow :as slow]
    [yang.python.antlr.int-ops-fixtures :as fixtures]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.integer :as integer]
    [yin.vm.integer-v3-fixtures :as fx]
    [yin.vm.integer.host :as integer-host]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]))


(defn- opts-under
  "The composition over the real modules, `integer` under `limits`."
  [limits]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (prelude/register-integer-module limits))})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- runners-under
  "Each VM's runner over the composition `opts`."
  [opts]
  {:ast-walker (fn [ast] (vm/value (vm/eval (tu/create-vm opts) ast))),
   :semantic (fn [ast]
               (vm/value (vm/run (load-semantic-ast (semantic/create-vm opts)
                                                    (vm/ast->datoms ast))))),
   :stack (fn [ast]
            (vm/value
              (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                     (assoc opts
                                            :contract vm/stack-contract))))),
   :register (fn [ast]
               (vm/value
                 (vm/run
                   (rvm/create-vm
                     (:image (rc/adapt (vm/ast->datoms ast)))
                     (assoc opts :contract vm/register-contract)))))})


(def runners
  (runners-under (opts-under {::integer/max-bits 100000,
                              ::integer/max-digits 4300})))


(def small-runners
  "Runners under limits a float key or hash breaches: 60 bits (2^-1074
   needs 1075, P = 2^61 - 1 needs 61) and 5 decimal digits."
  (runners-under (opts-under {::integer/max-bits 60,
                              ::integer/max-digits 5})))


(defn run-with-prelude
  "The value of `form` (prelude notation) after `prelude-ast`, on each
   of `runners` (default: the ordinary limits)."
  ([prelude-ast form] (run-with-prelude runners prelude-ast form))
  ([runners prelude-ast form]
   (let [ast (u/mark-tails (u/then prelude-ast (u/sexp->uast form)))
         attempt (fn [run]
                   (try (run ast)
                        (catch #?(:cljd Object :clj Exception :cljs :default) e
                          [:thrown (ex-message e)])))]
     (into {} (map (fn [[k run]] [k (attempt run)])) runners))))


(def caught
  '(fn [thunk]
     (py/try thunk
             (fn [e] (get (cell/get (get (cell/get e) :class)) :name))
             (fn [] :py/None))))


(defn host-floats
  "`x` with every {:py/float v} holding the host double of v: on JS a
  rendered integral float is a float64 wrapper and an expected one is
  a bare number, so compare them unwrapped."
  [x]
  (walk/postwalk
    (fn [n]
      (if (and (map? n) (= [:py/float] (keys n)))
        {:py/float (data/float-value (:py/float n))}
        n))
    x))


(defn exact-literals
  "`form` with every integer literal of magnitude 2^53 or more as an
   (integer/parse text): a bare one is not a canonical carrier on JS. A
   float literal such as 1.0E20 is also an integer there, so callers
   skip the float-only rows."
  [form]
  (walk/postwalk (fn [x]
                   (if (and (integer? x) (>= (abs x) 9007199254740992))
                     (list 'integer/parse (str x))
                     x))
                 form))


(defn canon-ints
  "`x` with every integer, host number or big carrier, as its decimal
   text, so a result compares equal across hosts. An integral double is
   one too: py/floor answers -3.0 on Dart, and a float's own type is
   carried by its {:py/float} wrapper. An integral double at or past
   2^62 stays a number: no host long holds it, and it is a float there."
  [x]
  (walk/postwalk (fn [n]
                   (cond
                     (integer-host/big-carrier? n) (str n)
                     (integer? n) (str n)
                     ;; magnitude first: rem of an infinity throws
                     (and (number? n) (< (abs n) 4611686018427387904)
                          (zero? (rem n 1)))
                     (str (long n))
                     :else n))
                 x))


(defn float-bits
  "`x` with every {:py/float v} holding v's IEEE bits as hex, so -0.0 is
   not 0.0 (host `=` says it is); every NaN is :nan, the one NaN."
  [x]
  (walk/postwalk
    (fn [n]
      (if (and (map? n) (= [:py/float] (keys n)))
        (let [v (data/float-value (:py/float n))]
          {:py/float (if (<= v v) (fx/double->bits v) :nan)})
        n))
    x))


(defn check-cases
  "Run `cases`, [form expected], as one program on each runner; floats
   compare by bits, integers by decimal text."
  [rs cases]
  (let [form (list 'let ['caught caught]
                   (reduce (fn [acc [form _]]
                             (list 'py/conj acc (exact-literals form)))
                           [] cases))]
    (doseq [[k result] (run-with-prelude rs prelude/uast form)]
      (is (= (canon-ints (float-bits (mapv second cases)))
             (canon-ints (float-bits result)))
          (str k)))))


(deftest exact-operators-test
  (check-cases
    runners
    [['(integer/format (py/add (py/int-lit "20000000000000") 1))
      "9007199254740993"]
     ['(py/sub (py/add (py/int-lit "10000000000000000") 1)
               (py/int-lit "10000000000000000")) 1]
     ['(integer/format (py/mul (py/int-lit "8000000000000000") true))
      "9223372036854775808"]
     ['(integer/format (py/neg (py/int-lit "10000000000000000")))
      "-18446744073709551616"]
     ['(py/pos true) 1]
     ['(py/eq (py/int-lit "20000000000001")
              (py/float (* (data/float-value 1) 4503599627370496 2)))
      false]
     ['(py/gt (py/int-lit "20000000000001")
              (py/float (* (data/float-value 1) 4503599627370496 2)))
      true]
     ['(caught (fn [] (py/int-result :yin.vm.integer/float-overflow)))
      "OverflowError"]
     ['(py/try
         (fn [] (py/int-result :yin.vm.integer/float-overflow))
         (fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
         (fn [] :py/None)) [{:py/str "int too large to convert to float"}]]
     ['(py/int-refusal? :yin.vm.integer/float-overflow) true]
     ['(caught (fn [] (py/add {:py/str "x"} 1))) "TypeError"]
     ['(py/zero? (py/int-lit "10000000000000000")) false]
     ['(caught (fn [] (py/sub 1 {:py/str "x"}))) "TypeError"]
     ['(caught (fn [] (py/mul 1 :py/None))) "TypeError"]
     ['(caught (fn [] (py/neg :py/None))) "TypeError"]
     ['(caught (fn [] (py/pos :py/None))) "TypeError"]
     ['(caught (fn [] (py/lt 1 :py/None))) "TypeError"]
     ['(caught (fn [] (py/truediv 1 :py/None))) "TypeError"]]))


(deftest index-range-repeat-test
  (check-cases
    runners
    [['(caught (fn []
                 (py/getitem (py/list [1])
                             (py/int-lit "10000000000000000"))))
      "IndexError"]
     ['(caught (fn []
                 (py/repeat (py/tuple [])
                            (py/int-lit "8000000000000000"))))
      "OverflowError"]
     ['(caught (fn []
                 (py/repeat (py/list [1])
                            (py/int-lit "20000000000000"))))
      "MemoryError"]
     ['(caught (fn []
                 (py/repeat {:py/str ""}
                            (py/neg (py/int-lit "8000000000000001")))))
      "OverflowError"]
     ['(py/repeat {:py/str ""} (py/int-lit "20000000000000"))
      {:py/str ""}]
     ['(py/repeat {:py/str "a"}
                  (py/neg (py/int-lit "20000000000000"))) {:py/str ""}]
     ['(caught (fn []
                 (py/repeat {:py/str "a"}
                            (py/int-lit "20000000000000"))))
      "MemoryError"]
     ['(caught (fn []
                 (py/repeat (py/tuple [1])
                            (py/int-lit "20000000000000"))))
      "MemoryError"]
     ['(caught (fn []
                 (py/repeat (py/list [])
                            (py/int-lit "8000000000000000"))))
      "OverflowError"]
     ['(caught (fn []
                 (py/repeat {:py/str "a"}
                            (py/int-lit "8000000000000000"))))
      "OverflowError"]
     ['(py/slice-positions
         (py/slice (py/neg (py/int-lit "10000000000000000"))
                   (py/int-lit "10000000000000000")
                   (py/int-lit "10000000000000000")) 3) [0]]
     ['(py/range-has?
         (py/range3 (py/int-lit "10000000000000000")
                    (py/int-lit "10000000000000009") 3)
         (py/int-lit "10000000000000006")) true]
     ['(py/range-at (py/range3 0 10 1)
                    (py/int-lit "10000000000000000")) :py/stop]
     ['(integer/format
         (py/range-len (py/range3 0 (py/int-lit "20000000000001") 1) 0))
      "9007199254740993"]
     ['(integer/format
         (py/range-len (py/range3 0 (py/int-lit "7fffffffffffffff") 1) 0))
      "9223372036854775807"]
     ['(caught (fn []
                 (py/range-len
                   (py/range3 0 (py/int-lit "8000000000000000") 1) 0)))
      "OverflowError"]
     ['(caught (fn []
                 (py/range-len
                   (py/range3 (py/neg (py/int-lit "8000000000000000"))
                              (py/int-lit "8000000000000000") 1) 0)))
      "OverflowError"]]))


(deftest range-small-profile-test
  (check-cases
    small-runners
    [['(py/range-at
         (py/range3 (py/neg (py/int-lit "fffffffffffffff"))
                    (py/int-lit "fffffffffffffff")
                    (py/int-lit "800000000000000")) 2) 1]
     ['(py/range-at
         (py/range3 (py/int-lit "ffffffffffffffe")
                    (py/int-lit "fffffffffffffff") 2) 1) :py/stop]
     ['(py/range-at
         (py/range3 (py/neg (py/int-lit "ffffffffffffffe"))
                    (py/neg (py/int-lit "fffffffffffffff")) -2) 1) :py/stop]
     ['(caught (fn [] (py/add (py/int-lit "fffffffffffffff") 1)))
      "MemoryError"]
     ['(py/range-at
         (py/range3 (py/int-lit "fffffffffffffff")
                    (py/neg (py/int-lit "fffffffffffffff"))
                    (py/neg (py/int-lit "800000000000000"))) 2) -1]
     ['(caught (fn []
                 (py/range-len
                   (py/range3 (py/neg (py/int-lit "fffffffffffffff"))
                              (py/int-lit "fffffffffffffff") 1) 0)))
      "MemoryError"]]))


(def decode-number
  '(fn [x]
     (if (= (get x :int) nil) x
         (py/int-result (integer/parse (get x :int) 16)))))


(def decode
  '(fn [x]
     (if (= (get x :tuple) nil) (decode-number x)
         (py/tuple
           (py/conj (py/conj [] (decode-number (get (get x :tuple) 0)))
                    (decode-number (get (get x :tuple) 1)))))))


(def operate
  '(fn [op a b]
     (let [bits
           (fn [op a b]
             (if (= op "bitand") (py/bitand a b)
                 (if (= op "bitor") (py/bitor a b)
                     (if (= op "bitxor") (py/bitxor a b)
                         (if (= op "invert") (py/invert a)
                             (if (= op "lshift") (py/lshift a b)
                                 (py/rshift a b)))))))
           division
           (fn [op a b]
             (if (= op "truediv") (py/truediv a b)
                 (if (= op "floordiv") (py/floordiv a b)
                     (if (= op "mod") (py/mod a b)
                         (if (= op "divmod") (py/divmod a b)
                             (if (= op "pow") (py/pow a b)
                                 (bits op a b)))))))
           comparison
           (fn [op a b]
             (if (= op "lt") (py/lt a b)
                 (if (= op "le") (py/le a b)
                     (if (= op "gt") (py/gt a b)
                         (if (= op "ge") (py/ge a b)
                             (if (= op "eq") (py/eq a b)
                                 (if (= op "ne") (py/ne a b)
                                     (division op a b))))))))]
       (if (= op "add") (py/add a b)
           (if (= op "sub") (py/sub a b)
               (if (= op "mul") (py/mul a b)
                   (if (= op "neg") (py/neg a)
                       (comparison op a b))))))))


(defn fixture-form
  "A guest loop evaluates every row, returning only mismatch indices."
  [rows]
  (list 'let
        ['decode-number decode-number 'decode decode 'operate operate
         'rows rows
         'loop
         '(fn [self i failures]
            (if (< i (data/count rows))
              (let [row (get rows i)
                    answer (cell/new nil)
                    _ (py/try
                        (fn []
                          (cell/set! answer
                                     (operate (get row 0)
                                              (decode (get row 1))
                                              (decode (get row 2)))))
                        (fn [e]
                          (cell/set!
                            answer
                            (data/str-concat
                              "!" (get (cell/get (get (cell/get e) :class))
                                       :name))))
                        (fn [] :py/None))
                    result (cell/get answer)]
                (self self (+ i 1)
                      (if (let [expected (decode (get row 3))]
                            (if (= (py/kind expected) :tuple)
                              (data/content= (get result :items)
                                             (get expected :items))
                              (data/content= result expected)))
                        failures (conj failures i))))
              failures))]
        '(loop loop 0 [])))


(deftest fixture-parser-test
  (is (= (fixtures/parse "int-ops-v1\nCPython 3.9.6\nadd\t1\t1\t2\n")
         (fixtures/parse
           "\nint-ops-v1\n\nCPython 3.9.6\n\nadd\t1\t1\t2\n\n"))))


(deftest ^:slow cpython-operator-fixture-test
  (slow/guard
    "cpython-operator-fixture-test"
    (fn []
      ;; 400-row programs: one 6,650-row literal program throws a host
      ;; RangeError on Dart (a size limit, not an operator); every row
      ;; still runs
      (let [rows (fixtures/read-file)]
        (doseq [[i batch] (map-indexed vector (partition-all 400 rows))
                [k result] (do (println "CPython batch" i)
                               (run-with-prelude prelude/uast
                                                 (fixture-form (vec batch))))]
          (is (= [] result)
              (str k " batch " i " mismatched fixture rows " result)))))))


(deftest nan-comparisons-test
  (check-cases
    runners
    [['(py/lt (py/int-lit "20000000000001")
              (py/float (data/float-value ##NaN))) false]
     ['(py/le (py/float (data/float-value ##NaN))
              (py/int-lit "20000000000001")) false]
     ['(py/gt (py/int-lit "20000000000001")
              (py/float (data/float-value ##NaN))) false]
     ['(py/ge (py/float (data/float-value ##NaN))
              (py/int-lit "20000000000001")) false]
     ['(py/eq (py/int-lit "20000000000001")
              (py/float (data/float-value ##NaN))) false]
     ['(py/ne (py/float (data/float-value ##NaN))
              (py/int-lit "20000000000001")) true]]))


(deftest mixed-float-overflow-test
  (check-cases
    runners
    [['(caught (fn [] (py/add (integer/pow 10 400) (py/float 1.5))))
      "OverflowError"]
     ['(caught (fn [] (py/sub (py/float 1.5) (integer/pow 10 400))))
      "OverflowError"]
     ['(caught (fn [] (py/mul (integer/pow 10 400) (py/float 0.5))))
      "OverflowError"]
     ['(caught (fn []
                 (py/truediv (integer/pow 10 400)
                             (py/float (data/float-value 0)))))
      "OverflowError"]
     ['(caught (fn [] (py/truediv (py/float 1.5) (integer/pow 10 400))))
      "OverflowError"]]))


(deftest exact-division-power-test
  (check-cases
    runners
    [['(integer/format (py/floordiv (py/neg (py/int-lit
                                              "10000000000000001")) 3))
      "-6148914691236517206"]
     ['(py/mod (py/neg (py/int-lit "10000000000000001")) 3) 1]
     ['(integer/format
         (py/mod 7 (py/neg (py/int-lit "10000000000000000"))))
      "-18446744073709551609"]
     ['(let [qr (get (py/divmod
                       (py/neg (py/int-lit "10000000000000001"))
                       (py/int-lit "10000000000000000")) :items)]
         (py/conj (py/conj [] (get qr 0)) (integer/format (get qr 1))))
      [-2 "18446744073709551615"]]
     ['(py/floordiv true true) 1]
     ['(py/floordiv (py/neg (py/int-lit "10000000000000000"))
                    (py/float (* (data/float-value 1) 4294967296)))
      {:py/float (data/float-value -4294967296)}]
     ['(py/mod (py/neg (py/int-lit "10000000000000001"))
               (py/float (data/float-value 3)))
      {:py/float (data/float-value 2)}]
     ['(py/floordiv (py/float 0.5)
                    (py/neg (py/int-lit "10000000000000000")))
      {:py/float (data/float-value -1)}]
     ['(py/mod true true) 0]
     ['(caught (fn [] (py/divmod (integer/pow 10 400) false)))
      "ZeroDivisionError"]
     ['(caught (fn [] (py/floordiv (integer/pow 10 400) 0)))
      "ZeroDivisionError"]
     ['(caught (fn [] (py/mod 1 0))) "ZeroDivisionError"]
     ['(py/floordiv (py/int-lit "10000000000000000")
                    (py/float (* (data/float-value 1) 4294967296)))
      {:py/float (data/float-value 4294967296)}]
     ['(py/mod (py/float 0.5) (py/int-lit "10000000000000000"))
      {:py/float 0.5}]
     ['(caught (fn [] (py/mod (integer/pow 10 400) (py/float 0.5))))
      "OverflowError"]
     ['(integer/format (py/pow -2 100))
      "1267650600228229401496703205376"]
     ['(py/pow 0 0) 1]
     ['(py/pow true (py/int-lit "10000000000000001")) 1]
     ['(py/pow -2 -3) {:py/float -0.125}]
     ['(caught (fn []
                 (py/pow -2 (py/int-lit "10000000000000001"))))
      "MemoryError"]
     ['(py/pow -1 (py/int-lit "10000000000000001")) -1]
     ;; a float power uses the exponent as a double, as CPython does:
     ;; 2^64 + 1 rounds to the even 2^64 (S4)
     ['(py/pow -1 (py/neg (py/int-lit "10000000000000001")))
      {:py/float (data/float-value 1)}]
     ['(py/pow (py/float (data/float-value -1))
               (py/int-lit "10000000000000001"))
      {:py/float (data/float-value 1)}]
     ['(caught (fn [] (py/pow 0 -1))) "ZeroDivisionError"]
     ['(caught (fn [] (py/pow (integer/pow 10 400) -1)))
      "OverflowError"]
     ['(py/truediv 5 2) {:py/float 2.5}]
     ['(py/truediv (py/int-lit "20000000000001") 2)
      {:py/float (data/float-value 4503599627370496)}]
     ['(py/truediv (py/int-lit "20000000000003") 2)
      {:py/float (data/float-value 4503599627370498)}]
     ['(py/truediv (integer/pow 2 1074) (integer/pow 2 1075))
      {:py/float 0.5}]
     ['(py/truediv (integer/pow 10 400) (integer/pow 10 398))
      {:py/float (data/float-value 100)}]
     ['(py/truediv (py/int-lit "20000000000001") 1)
      {:py/float (* (data/float-value 1) 4503599627370496 2)}]
     ['(py/truediv (py/int-lit "20000000000001")
                   (py/int-lit "20000000000003"))
      {:py/float 0.9999999999999998}]
     ['(caught (fn [] (py/truediv (integer/pow 10 400) 0)))
      "ZeroDivisionError"]
     ['(caught (fn [] (py/truediv (integer/pow 10 400) 1)))
      "OverflowError"]
     ['(integer/format (py/abs (py/neg (py/int-lit "10000000000000000"))))
      "18446744073709551616"]]))


(deftest power-bit-limit-test
  (check-cases small-runners
               [['(caught (fn [] (py/pow 2 60))) "MemoryError"]]))


(deftest ^:slow cpython-s3b-fixture-test
  (slow/guard
    "cpython-s3b-fixture-test"
    (fn []
      (let [rows (fixtures/read-file
                   "test/resources/yang/python/int-ops-v2.txt")]
        (doseq [[i batch] (map-indexed vector (partition-all 100 rows))
                [k result] (do (println "CPython batch" i)
                               (run-with-prelude prelude/uast
                                                 (fixture-form (vec batch))))]
          (is (= [] result) (str k " v2 batch " i " rows " result)))))))


(deftest float-division-edge-test
  (check-cases
    runners
    [['(py/eq (py/mod (py/float 0.5)
                      (py/float (data/float-value ##NaN))) 0) false]
     ['(py/eq (py/floordiv (py/float 0.5)
                           (py/float (data/float-value ##NaN))) 0) false]
     ['(py/floordiv (py/float (* (data/float-value 1) 4294967296
                                 4294967296)) true)
      {:py/float (* (data/float-value 1) 4294967296 4294967296)}]
     ['(caught (fn []
                 (py/floordiv (integer/pow 10 400)
                              (py/float (data/float-value 0)))))
      "OverflowError"]
     ['(caught (fn []
                 (py/mod (integer/pow 10 400)
                         (py/float (data/float-value 0)))))
      "OverflowError"]
     ['(caught (fn []
                 (py/divmod (integer/pow 10 400)
                            (py/float (data/float-value 0)))))
      "OverflowError"]]))


(deftest division-errors-test
  (check-cases
    runners
    [['(py/try
         (fn [] (py/floordiv (py/int-lit "10000000000000000") 0))
         (fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
         (fn [] :py/None))
      [{:py/str "integer division or modulo by zero"}]]
     ['(py/try
         (fn [] (py/truediv (integer/pow 10 400) 0))
         (fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
         (fn [] :py/None)) [{:py/str "division by zero"}]]
     ['(caught (fn [] (py/floordiv {:py/str "x"} 1))) "TypeError"]
     ['(caught (fn [] (py/mod 1 :py/None))) "TypeError"]
     ['(caught (fn [] (py/divmod :py/None 0))) "TypeError"]
     ['(caught (fn [] (py/pow {:py/str "x"} 1))) "TypeError"]
     ['(caught (fn []
                 (py/pow false
                         (py/neg (py/int-lit "10000000000000001")))))
      "ZeroDivisionError"]]))


(deftest exact-bitwise-shifts-test
  (check-cases
    runners
    [['(py/bitand true true) true]
     ['(py/bitor false true) true]
     ['(py/bitxor true true) false]
     ['(py/bitand true 3) 1]
     ['(py/bitand -6 3) 2]
     ['(integer/format (py/bitand -1 (py/int-lit "10000000000000001")))
      "18446744073709551617"]
     ['(integer/format (py/invert (py/int-lit "10000000000000000")))
      "-18446744073709551617"]
     ['(integer/format (py/invert (py/neg (py/int-lit "10000000000000000"))))
      "18446744073709551615"]
     ['(integer/format (py/lshift 1 54)) "18014398509481984"]
     ['(integer/format (py/lshift (py/int-lit "10000000000000001") 0))
      "18446744073709551617"]
     ['(integer/format (py/rshift (py/int-lit "10000000000000001") 0))
      "18446744073709551617"]
     ['(py/rshift -5 1) -3]
     ['(py/lshift 0 (py/int-lit "10000000000000000")) 0]
     ['(py/rshift -5 (py/int-lit "10000000000000000")) -1]
     ['(py/rshift 5 (py/int-lit "10000000000000000")) 0]
     ['(caught (fn [] (py/lshift 0 -1))) "ValueError"]
     ['(caught (fn []
                 (py/rshift 0 (py/neg (py/int-lit "10000000000000000")))))
      "ValueError"]
     ['(py/try (fn [] (py/lshift 1 -1))
               (fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
               (fn [] :py/None)) [{:py/str "negative shift count"}]]
     ['(caught (fn [] (py/bitand (py/float 0.5) 1))) "TypeError"]
     ['(caught (fn [] (py/bitor 1 :py/None))) "TypeError"]
     ['(caught (fn [] (py/bitxor :py/None 1))) "TypeError"]
     ['(caught (fn [] (py/invert :py/None))) "TypeError"]
     ['(caught (fn [] (py/lshift :py/None -1))) "TypeError"]
     ['(caught (fn [] (py/rshift 1 (py/float 0.5)))) "TypeError"]]))


(deftest shift-bit-limit-test
  (check-cases
    small-runners
    [['(caught (fn [] (py/lshift 1 60))) "MemoryError"]
     ['(caught (fn [] (py/lshift 1 (py/int-lit "800000000000000"))))
      "MemoryError"]
     ['(py/lshift 0 (py/int-lit "800000000000000")) 0]]))


(deftest float-power-exponent-limit-test
  ;; The exponent converts to float as CPython's does: it overflows from
  ;; 2^1024 - 2^970, the first integer that rounds past the largest double
  (let [edge '(integer/sub (integer/pow 2 1024) (integer/pow 2 970))]
    (check-cases
      runners
      [[(list 'caught (list 'fn [] (list 'py/pow '(py/float
                                                    (data/float-value 1))
                                         edge)))
        "OverflowError"]
       [(list 'caught (list 'fn [] (list 'py/pow '(py/float
                                                    (data/float-value 1))
                                         (list 'integer/neg edge))))
        "OverflowError"]
       [(list 'py/pow '(py/float (data/float-value 1))
              (list 'integer/sub edge 1))
        {:py/float (data/float-value 1)}]
       [(list 'py/pow '(py/float (data/float-value 1))
              (list 'integer/sub 1 edge))
        {:py/float (data/float-value 1)}]
       [(list 'py/try
              (list 'fn [] (list 'py/pow '(py/float (data/float-value 1))
                                 edge))
              '(fn [e] (get (get (get (cell/get e) :attrs) "args") :items))
              '(fn [] :py/None))
        [{:py/str "int too large to convert to float"}]]])))


(deftest ^:slow cpython-s3c-fixture-test
  (slow/guard
    "cpython-s3c-fixture-test"
    (fn []
      (let [rows (fixtures/read-file
                   "test/resources/yang/python/int-ops-v3.txt")]
        (doseq [[i batch] (map-indexed vector (partition-all 100 rows))
                [k result] (run-with-prelude prelude/uast
                                             (fixture-form (vec batch)))]
          (is (= [] result) (str k " v3 batch " i " rows " result)))))))


(deftest small-profile-hash-zero-test
  (check-cases
    small-runners
    [['(caught (fn [] (py/hash 0))) "MemoryError"]
     ['(caught (fn [] (py/hash false))) "MemoryError"]
     ['(caught (fn [] (py/hash (* -1 0)))) "MemoryError"]
     ['(py/hash (py/float (data/float-value 0))) 0]]))
