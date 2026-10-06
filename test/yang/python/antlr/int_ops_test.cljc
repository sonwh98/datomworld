(ns yang.python.antlr.int-ops-test
  "C3 S3-A exact operators and range/index audit on all four VMs."
  (:require
    [clojure.test :refer [deftest is]]
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


(def ^:private runners
  (runners-under (opts-under {::integer/max-bits 100000,
                              ::integer/max-digits 4300})))


(def ^:private small-runners
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


(defn check-cases
  [rs cases]
  (let [form (list 'let ['caught caught]
                   (reduce (fn [acc [form _]] (list 'py/conj acc form))
                           [] cases))]
    (doseq [[k result] (run-with-prelude rs prelude/uast form)]
      (is (= (mapv second cases) result) (str k)))))


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


(def decode
  '(fn [x]
     (if (= (get x :int) nil) x
         (py/int-result (integer/parse (get x :int) 16)))))


(def operate
  '(fn [op a b]
     (if (= op "add") (py/add a b)
         (if (= op "sub") (py/sub a b)
             (if (= op "mul") (py/mul a b)
                 (if (= op "neg") (py/neg a)
                     (if (= op "lt") (py/lt a b)
                         (if (= op "le") (py/le a b)
                             (if (= op "gt") (py/gt a b)
                                 (if (= op "ge") (py/ge a b)
                                     (if (= op "eq") (py/eq a b)
                                         (if (= op "ne") (py/ne a b)
                                             (py/truediv a b)))))))))))))


(defn fixture-form
  "A guest loop evaluates every row, returning only mismatch indices."
  [rows]
  (list 'let
        ['decode decode 'operate operate
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
                      (if (data/content= result (decode (get row 3)))
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
                [k result] (run-with-prelude prelude/uast
                                             (fixture-form (vec batch)))]
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
