(ns yang.typescript.antlr.prelude-parity-test
  "The `js` prelude's semantics on all four VMs and every host this runs on
   (T1 steps S1 and S2): two-form strings, ES Number::toString, ToNumber,
   ToInt32, the operator family with exact BigInt arms, equality and order,
   the ordered own-key map, object reads with primitive receivers, function
   calls, the Error family, the continuation escapes, `js/init!` and the
   console boundary.

   Numbers are float64 content: a test writes one as the marker `{:n x}`,
   which becomes `(data/float64 x)` in a program and `(cbor/float64 x)` in an
   expectation (`:neg-zero`, `:nan`, `:inf` and `:-inf` name the specials),
   and results compare by `cbor/content=`, which is kind strict: a bare 1 (a
   bigint) is never a float64 1."
  (:require [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.semantic.derive :as derive]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [dao.test-slow :as slow]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.render :as render]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.integer :as integer]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(def ^:private opts
  "The composition over the real modules."
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                (data/register-data-module {::data/max-items 1048576})
                (prelude/register-integer-module {::integer/max-bits 100000,
                                                  ::integer/max-digits 4300})
                prelude/admit)})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader semantic/load-ast
                        vm/ast-contract))


(def vm-runners
  "Each VM's runner over the composition: ast to the finished VM."
  {:ast-walker (fn [ast] (vm/eval (tu/create-vm opts) ast)),
   :semantic (fn [ast]
               (vm/run (load-semantic-ast (semantic/create-vm opts)
                                          (vm/ast->datoms ast)))),
   :stack (fn [ast]
            (vm/run (dvm/create-vm
                      (:image (derive/stack-image
                                (:vector (sr-linearize/project ast))))
                      (assoc opts :contract vm/stack-contract)))),
   :register (fn [ast]
               (vm/run
                 (rvm/create-vm
                   (derive/register-image (:vector (sr-linearize/project ast)))
                   (assoc opts :contract vm/register-contract))))})


(def runners
  (into {}
        (map (fn [[k run]] [k (fn [ast] (vm/value (run ast)))]))
        vm-runners))


(defn run-with-prelude
  "The value of `form` (prelude notation) after `prelude-ast`, on each VM."
  [prelude-ast form]
  (let [ast (u/mark-tails (u/then prelude-ast (u/sexp->uast form)))
        attempt (fn [run]
                  (try (run ast)
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e)])))]
    (into {} (map (fn [[k run]] [k (attempt run)])) runners)))


(defn- special
  [x]
  (case x
    :neg-zero (* -1.0 0.0)
    :nan (- ##Inf ##Inf)
    :inf ##Inf
    :-inf ##-Inf
    x))


(defn- marker?
  [x]
  (and (map? x) (= [:n] (keys x))))


(defn program-form
  "`form` with every `{:n x}` marker as float64 content, embedded as a
   literal (on JS the carrier: a bare integral Number is an integer, and
   one past 2^53 is refused)."
  [form]
  (walk/postwalk (fn [x] (if (marker? x) (cbor/float64 (special (:n x))) x))
                 form))


(defn expected-value
  "`expected` with every `{:n x}` marker as float64 content."
  [expected]
  (program-form expected))


(defn- same?
  [a b]
  (cbor/content= a b))


(defn- throws
  "A form that answers the `name` of the error `expr` throws, or the value."
  [expr]
  (list 'js/try
        (list 'fn [] expr)
        '(fn [e] (js/get-prop e {:js/str "name"}))))


(defn- av
  "A program form building the argument vector of `xs`."
  [& xs]
  (reduce (fn [acc a] (list 'js/vconj acc a)) [] xs))


(defn- call-method
  [recv method & args]
  (list 'js/call
        (list 'js/get-value recv {:js/str method})
        recv
        (reduce (fn [acc a] (list 'js/vconj acc a)) [] args)))


(def ^:private str-cases
  "[form expected]: strings, S1."
  [['(js/mk-str [72 105]) {:js/str "Hi"}]
   ['(js/mk-str [55357]) {:js/units [55357]}]
   ['(js/mk-str [55357 56832]) {:js/str "😀"}]
   ['(js/mk-str []) {:js/str ""}]
   ['(js/str-concat {:js/units [55357]} {:js/units [56832]})
    {:js/str "😀"}]
   ['(js/str-concat {:js/str "a"} {:js/units [55357]}) {:js/units [97 55357]}]
   ['(js/str-concat {:js/str "a"} {:js/str "b"}) {:js/str "ab"}]
   ['(js/str-unit-length {:js/str "😀"}) 2]
   ['(js/str-unit-length {:js/units [55357]}) 1]
   ['(js/str-unit-at {:js/str "😀"} 1) 56832]
   ['(js/str-slice {:js/str "😀"} 0 1) {:js/units [55357]}]
   ['(js/str-slice {:js/str "abc"} 1 3) {:js/str "bc"}]
   ['(js/str-compare {:js/str "a"} {:js/str "b"}) -1]
   ['(js/str-compare {:js/str "b"} {:js/str "a"}) 1]
   ['(js/str-compare {:js/str "ab"} {:js/str "a"}) 1]
   ['(js/str-compare {:js/str "a"} {:js/str "a"}) 0]
   ;; by code unit, not code point: U+FFFF sorts after a surrogate pair
   ['(js/str-compare (js/mk-str [65535]) (js/mk-str [55357 56832])) 1]
   ['(js/to-string {:n 1}) {:js/str "1"}]
   ['(js/to-string 12) {:js/str "12"}]
   ['(js/to-string true) {:js/str "true"}]
   ['(js/to-string :js/undefined) {:js/str "undefined"}]
   ['(js/to-string :js/null) {:js/str "null"}]
   ['(js/typeof {:n 1}) {:js/str "number"}]
   ['(js/typeof 1) {:js/str "bigint"}]
   ['(js/typeof {:js/str "s"}) {:js/str "string"}]
   ['(js/typeof :js/null) {:js/str "object"}]
   ['(js/typeof :js/undefined) {:js/str "undefined"}]
   ['(js/typeof true) {:js/str "boolean"}]])


(def ^:private number-text-cases
  "[value expected-text]: ES Number::toString(10)."
  [[0.1 "0.1"] [1.5 "1.5"] [100.0 "100"] [123.456 "123.456"]
   [1.0E21 "1e+21"] [1.0E-7 "1e-7"] [0.000001 "0.000001"]
   [1.2345678901234568E20 "123456789012345680000"]
   [1.0E20 "100000000000000000000"] [1.5E-7 "1.5e-7"] [-2.5 "-2.5"]
   [5.0E-324 "5e-324"] [1.7976931348623157E308 "1.7976931348623157e+308"]
   [1.0E300 "1e+300"] [2.5E25 "2.5e+25"] [0.30000000000000004
                                          "0.30000000000000004"]
   [:neg-zero "0"] [:nan "NaN"] [:inf "Infinity"] [:-inf "-Infinity"]])


(def ^:private number-cases
  "[form expected]: numbers and operators, S1."
  [['(js/add {:n 0.1} {:n 0.2}) {:n 0.30000000000000004}]
   ['(js/add {:n 1} {:n 2}) {:n 3}]
   ['(js/add {:js/str "a"} {:n 1}) {:js/str "a1"}]
   ['(js/add {:n 1} {:js/str "a"}) {:js/str "1a"}]
   ['(js/add {:js/str "x"} :js/undefined) {:js/str "xundefined"}]
   ['(js/add true {:n 1}) {:n 2}]
   ['(js/add :js/null {:n 1}) {:n 1}]
   ['(js/add :js/undefined {:n 1}) {:n :nan}]
   ['(js/sub {:js/str "5"} {:n 2}) {:n 3}]
   ['(js/mul {:n 3} {:n 4}) {:n 12}]
   ['(js/div {:n 1} {:n 4}) {:n 0.25}]
   ['(js/div {:n 1} {:n 0}) {:n :inf}]
   ['(js/div {:n 1} (js/neg {:n 0})) {:n :-inf}]
   ['(js/div (js/neg {:n 1}) {:n 0}) {:n :-inf}]
   ['(js/div {:n 0} {:n 0}) {:n :nan}]
   ['(js/div {:n 1} (js/neg (js/neg {:n 0}))) {:n :inf}]
   ['(js/neg {:n 0}) {:n :neg-zero}]
   ['(js/neg (js/neg {:n 0})) {:n 0}]
   ['(js/neg {:n 2.5}) {:n -2.5}]
   ['(js/pos {:js/str " 7 "}) {:n 7}]
   ['(js/mod {:n 5.5} {:n 2}) {:n 1.5}]
   ['(js/mod {:n -5.5} {:n 2}) {:n -1.5}]
   ['(js/mod {:n 5} {:n -2}) {:n 1}]
   ['(js/mod {:n -4} {:n 2}) {:n :neg-zero}]
   ['(js/mod {:n 1} {:n 0}) {:n :nan}]
   ['(js/mod {:n :inf} {:n 2}) {:n :nan}]
   ['(js/mod {:n 5} {:n :inf}) {:n 5}]
   ['(js/mod {:n 1.0E20} {:n 3}) {:n 1}]
   ['(js/mod {:n 1.0E300} {:n 7}) {:n 1}]
   ['(js/pow {:n 2} {:n 10}) {:n 1024}]
   ['(js/pow {:n 2} {:n -1}) {:n 0.5}]
   ['(js/pow {:n 2} {:n -1074}) {:n 5.0E-324}]
   ['(js/pow {:n 0} {:n -1}) {:n :inf}]
   ['(js/pow (js/neg {:n 0}) {:n -1}) {:n :-inf}]
   ['(js/pow {:n -2} {:n 3}) {:n -8}]
   ['(js/pow {:n 2} {:n 0}) {:n 1}]
   ['(js/pow {:n :nan} {:n 0}) {:n 1}]
   ['(js/pow {:n 1} {:n :inf}) {:n :nan}]
   ['(js/pow {:n 2} {:n :inf}) {:n :inf}]
   ['(js/pow {:n 0.5} {:n :inf}) {:n 0}]
   ['(js/strict-eq (js/pow {:n 2} {:n 53}) (js/pow {:n 2} {:n 53})) true]
   ['(js/pow {:n 4} {:n 0.5}) :throws-unsupported]
   ['(js/strict-eq (js/add (js/pow {:n 2} {:n 53}) {:n 1}) (js/pow {:n 2} {:n 53}))
    true]
   ['(js/bit-and {:n 5} {:n 3}) {:n 1}]
   ['(js/bit-or {:n 5} {:n 3}) {:n 7}]
   ['(js/bit-xor {:n 5} {:n 3}) {:n 6}]
   ['(js/bit-not {:n 5}) {:n -6}]
   ['(js/bit-and {:n 4294967295} {:n 255}) {:n 255}]
   ['(js/bit-or {:n 4294967296} {:n 1}) {:n 1}]
   ['(js/bit-or {:n 2147483648} {:n 0}) {:n -2147483648}]
   ['(js/shl {:n 1} {:n 31}) {:n -2147483648}]
   ['(js/shl {:n 1} {:n 32}) {:n 1}]
   ['(js/sar (js/neg {:n 16}) {:n 2}) {:n -4}]
   ['(js/shr (js/neg {:n 1}) {:n 0}) {:n 4294967295}]
   ['(js/shr (js/neg {:n 16}) {:n 28}) {:n 15}]
   ['(js/bit-or {:n 1.9} {:n 0}) {:n 1}]
   ['(js/bit-or {:n :nan} {:n 0}) {:n 0}]
   ['(js/bit-or {:n -1.9} {:n 0}) {:n -1}]
   ['(js/to-int32 {:n 4294967297}) {:n 1}]
   ['(js/to-int32 {:n 2147483648}) {:n -2147483648}]
   ['(js/to-uint32 (js/neg {:n 1})) {:n 4294967295}]
   ['(js/to-uint32 {:js/str "12"}) {:n 12}]
   ;; ToNumber over the StringNumericLiteral grammar
   ['(js/to-number {:js/str " 12 "}) {:n 12}]
   ['(js/to-number {:js/str ""}) {:n 0}]
   ['(js/to-number {:js/str "  "}) {:n 0}]
   ['(js/to-number {:js/str "0x10"}) {:n 16}]
   ['(js/to-number {:js/str "0b101"}) {:n 5}]
   ['(js/to-number {:js/str "0o17"}) {:n 15}]
   ['(js/to-number {:js/str "1e3"}) {:n 1000}]
   ['(js/to-number {:js/str "1E-2"}) {:n 0.01}]
   ['(js/to-number {:js/str "Infinity"}) {:n :inf}]
   ['(js/to-number {:js/str "-Infinity"}) {:n :-inf}]
   ['(js/to-number {:js/str "12px"}) {:n :nan}]
   ['(js/to-number {:js/str "-0"}) {:n :neg-zero}]
   ['(js/to-number {:js/str ".5"}) {:n 0.5}]
   ['(js/to-number {:js/str "5."}) {:n 5}]
   ['(js/to-number {:js/str "."}) {:n :nan}]
   ['(js/to-number {:js/str "+1.5e+2"}) {:n 150}]
   ['(js/to-number {:js/str "1e"}) {:n :nan}]
   ['(js/to-number {:js/str "0x"}) {:n :nan}]
   ['(js/to-number {:js/str "-0x10"}) {:n :nan}]
   ['(js/to-number {:js/str "1e400"}) {:n :inf}]
   ['(js/to-number {:js/str "0.1"}) {:n 0.1}]
   ['(js/to-number {:js/str "1_0"}) {:n :nan}]
   ['(js/to-number (js/mk-str [160 49 8232])) {:n 1}]
   ['(js/to-number {:js/units [55357]}) {:n :nan}]
   ['(js/to-number :js/undefined) {:n :nan}]
   ['(js/to-number :js/null) {:n 0}]
   ['(js/to-number true) {:n 1}]
   ['(js/to-boolean {:n 0}) false]
   ['(js/to-boolean {:n :nan}) false]
   ['(js/to-boolean {:n :neg-zero}) false]
   ['(js/to-boolean {:n 2}) true]
   ['(js/to-boolean {:js/str ""}) false]
   ['(js/to-boolean {:js/str "0"}) true]
   ['(js/to-boolean 0) false]
   ['(js/to-boolean 3) true]
   ['(js/to-boolean :js/null) false]
   ['(js/to-boolean :js/undefined) false]
   ;; BigInt arms
   ['(js/add 2 3) 5]
   ['(js/sub 2 3) -1]
   ['(js/mul 94906265 94906265) 9007199136250225]
   ['(js/to-string (js/pow 2 70)) {:js/str "1180591620717411303424"}]
   ['(js/to-string (js/mul (js/pow 2 70) -3)) {:js/str "-3541774862152233910272"}]
   ['(js/div 7 2) 3]
   ['(js/div -7 2) -3]
   ['(js/mod -7 2) -1]
   ['(js/mod 7 -2) 1]
   ['(js/pow 2 10) 1024]
   ['(js/neg 5) -5]
   ['(js/bit-not 5) -6]
   ['(js/bit-and -6 3) 2]
   ['(js/shl 1 4) 16]
   ['(js/shl 16 -2) 4]
   ['(js/sar -16 2) -4]
   ['(js/sar 16 -1) 32]
   ['(js/add 2 {:js/str "x"}) {:js/str "2x"}]
   ['(js/to-string 123) {:js/str "123"}]
   ['(js/to-string -5) {:js/str "-5"}]
   [(throws '(js/add 1 {:n 1})) {:js/str "TypeError"}]
   [(throws '(js/mul {:n 1} 2)) {:js/str "TypeError"}]
   [(throws '(js/shr 1 1)) {:js/str "TypeError"}]
   [(throws '(js/pos 1)) {:js/str "TypeError"}]
   [(throws '(js/div 1 0)) {:js/str "RangeError"}]
   [(throws '(js/mod 1 0)) {:js/str "RangeError"}]
   [(throws '(js/pow 2 -1)) {:js/str "RangeError"}]
   [(throws '(js/to-number 1)) {:js/str "TypeError"}]
   ;; equality and order
   ['(js/strict-eq {:n 1} {:n 1}) true]
   ['(js/strict-eq {:n 0} (js/neg {:n 0})) true]
   ['(js/strict-eq {:n :nan} {:n :nan}) false]
   ['(js/strict-eq {:n 1} 1) false]
   ['(js/strict-eq {:js/str "a"} {:js/str "a"}) true]
   ['(js/strict-eq {:js/units [55357]} {:js/units [55357]}) true]
   ['(js/strict-eq {:js/str "1"} {:n 1}) false]
   ['(js/strict-eq :js/null :js/undefined) false]
   ['(js/strict-eq 2 2) true]
   ['(js/strict-eq true true) true]
   ['(js/same-value {:n :nan} {:n :nan}) true]
   ['(js/same-value {:n 0} (js/neg {:n 0})) false]
   ['(js/same-value-zero {:n 0} (js/neg {:n 0})) true]
   ['(js/same-value-zero {:n :nan} {:n :nan}) true]
   ['(js/same-value-zero {:n 1} {:n 2}) false]
   ['(js/loose-eq :js/null :js/undefined) true]
   ['(js/loose-eq :js/null {:n 0}) false]
   ['(js/loose-eq {:n 1} {:js/str "1"}) true]
   ['(js/loose-eq {:js/str "1"} {:n 1}) true]
   ['(js/loose-eq {:js/str ""} {:n 0}) true]
   ['(js/loose-eq {:js/str "a"} {:n 0}) false]
   ['(js/loose-eq true {:n 1}) true]
   ['(js/loose-eq true {:js/str "1"}) true]
   ['(js/loose-eq false {:js/str ""}) true]
   ['(js/loose-eq 1 {:n 1}) true]
   ['(js/loose-eq {:n 1} 1) true]
   ['(js/loose-eq 1 {:n 1.5}) false]
   ['(js/loose-eq 1 {:js/str "1"}) true]
   ['(js/loose-eq {:js/str " 1 "} 1) true]
   ['(js/loose-eq 1 {:js/str "x"}) false]
   ['(js/loose-eq (js/add (js/pow 2 53) 1) (js/pow {:n 2} {:n 53})) false]
   ['(js/loose-eq (js/pow 2 53) (js/pow {:n 2} {:n 53})) true]
   ['(js/loose-eq 1 {:n :nan}) false]
   ['(js/loose-eq {:n :nan} {:n :nan}) false]
   ['(js/loose-eq :js/undefined {:n 0}) false]
   ['(js/loose-eq :js/null false) false]
   ['(js/lt {:n 1} {:n 2}) true]
   ['(js/lt {:n 2} {:n 1}) false]
   ['(js/lt {:n :nan} {:n 1}) false]
   ['(js/ge {:n :nan} {:n 1}) false]
   ['(js/le {:n :nan} {:n 1}) false]
   ['(js/gt {:n :nan} {:n 1}) false]
   ['(js/le {:n 1} {:n 1}) true]
   ['(js/ge {:n 1} {:n 1}) true]
   ['(js/gt {:n 2} {:n 1}) true]
   ['(js/lt {:js/str "a"} {:js/str "b"}) true]
   ['(js/lt {:js/str "10"} {:js/str "9"}) true]
   ['(js/lt {:js/str "10"} {:n 9}) false]
   ['(js/lt {:js/str "a"} {:n 1}) false]
   ['(js/lt 1 {:n 1.5}) true]
   ['(js/lt {:n 0.5} 1) true]
   ['(js/lt 2 {:n 1.5}) false]
   ['(js/lt 1 {:n :inf}) true]
   ['(js/lt {:n :-inf} 1) true]
   ['(js/lt 1 {:n :nan}) false]
   ['(js/ge 1 {:n :nan}) false]
   ['(js/lt 1 2) true]
   ['(js/lt 1 {:js/str "2"}) true]
   ['(js/lt {:js/str "x"} 1) false]
   ['(js/ge {:js/str "x"} 1) false]
   ['(js/lt (js/add (js/pow 2 53) 1) (js/pow {:n 2} {:n 53})) false]
   ['(js/gt (js/add (js/pow 2 53) 1) (js/pow {:n 2} {:n 53})) true]
   ['(js/lt {:n 1} {:js/str "2"}) true]
   ['(js/lt (js/mk-str [65535]) (js/mk-str [55357 56832])) false]
   ['(js/gt (js/mk-str [65535]) (js/mk-str [55357 56832])) true]])


(defn- split-cases
  "`cases` with each :throws-unsupported expectation as the form wrapped to
   answer the error name."
  [cases]
  (mapv (fn [[form expected]]
          (if (= :throws-unsupported expected)
            [(throws form) {:js/str "TypeError"}]
            [form expected]))
        cases))


(defn- check-cases
  "Run `cases` as one vector program on every VM and compare each result."
  [cases]
  (let [cases (split-cases cases)
        form (program-form
               (reduce (fn [acc [f _]] (list 'js/vconj acc f)) [] cases))
        expected (mapv (fn [[_ e]] (expected-value e)) cases)]
    (doseq [[k result] (run-with-prelude prelude/uast form)]
      (testing (str k)
        (if (and (vector? result) (= (count expected) (count result)))
          (doseq [[i exp act] (map vector (range) expected result)]
            (is (same? exp act) (str (pr-str (first (nth cases i))) " => "
                                     (pr-str act) ", expected " (pr-str exp))))
          (is (nil? result) "a vector of results"))))))


(defn- strings-and-numbers-on-every-vm
  []
  (testing "strings: two forms, normalized by one door"
    (check-cases str-cases))
  (testing "numbers, BigInt, equality and order"
    (check-cases number-cases)))


(deftest ^:slow strings-and-numbers-on-every-vm-test
  (slow/guard "strings-and-numbers-on-every-vm-test"
              strings-and-numbers-on-every-vm))


(defn- number->string-parity-on-every-vm
  []
  (testing "the guest's number->string is the boundary renderer's, over the
            pinned doubles and the JS thresholds"
    (let [vals (mapv first number-text-cases)
          form (program-form
                 (reduce (fn [acc v]
                           (list 'js/vconj acc
                                 (list 'js/number-text {:n v})))
                         []
                         vals))
          expected (mapv second number-text-cases)]
      (doseq [[k result] (run-with-prelude prelude/uast form)]
        (testing (str k)
          (is (= expected result)))))
    (doseq [[v text] number-text-cases]
      (is (= text (render/number->string (cbor/float64 (special v))))
          (str v)))))


(deftest number->string-parity-on-every-vm-test
  (slow/guard "number->string-parity-on-every-vm-test"
              number->string-parity-on-every-vm))


;; =============================================================================
;; S2: objects (read side), functions, errors, control flow
;; =============================================================================

(def ^:private emoji "\ud83d\ude00")


(defn- error-ctor
  [name & args]
  (list 'js/construct
        (list 'js/get-prop 'js.rt/global {:js/str name})
        (reduce (fn [acc a] (list 'js/vconj acc a)) [] args)))


(def ^:private object-cases
  "[form expected]: reads, method calls on primitive receivers, errors."
  [;; the ordered own-key map: integer keys ascending, then insertion order
   ['(js/om-keys
       (js/om-put
         (js/om-put (js/om-put (js/om-put (js/om-new) {:js/str "b"} 1)
                               {:js/str "10"}
                               2)
                    {:js/str "a"}
                    3)
         {:js/str "9"}
         4))
    [{:js/str "9"} {:js/str "10"} {:js/str "b"} {:js/str "a"}]]
   ['(js/om-keys (js/om-put (js/om-put (js/om-new) {:js/str "x"} 1)
                            {:js/str "x"}
                            2))
    [{:js/str "x"}]]
   ['(get (js/om-get (js/om-put (js/om-new) {:js/str "x"} 1) {:js/str "x"}) :a)
    nil]
   ['(js/index-key-value {:js/str "007"}) -1]
   ['(js/index-key-value {:js/str "7"}) 7]
   ['(js/index-key-value {:js/str "4294967295"}) -1]
   ['(js/index-key-value {:js/str "4294967294"}) 4294967294]
   ['(js/index-key-value {:js/str "-1"}) -1]
   ;; primitive receivers: indexing, length, methods
   [(list 'js/get-value {:js/str emoji} {:js/str "length"}) {:n 2}]
   [(list 'js/get-value {:js/str emoji} {:js/str "0"}) {:js/units [55357]}]
   [(list 'js/get-value {:js/str emoji} {:js/str "2"}) :js/undefined]
   [(list 'js/get-value {:js/str "abc"} {:js/str "1"}) {:js/str "b"}]
   [(call-method {:js/str emoji} "charCodeAt" {:n 1}) {:n 56832}]
   [(call-method {:js/str emoji} "charCodeAt" {:n 2}) {:n :nan}]
   [(call-method {:js/str emoji} "codePointAt" {:n 0}) {:n 128512}]
   [(call-method {:js/str emoji} "codePointAt" {:n 1}) {:n 56832}]
   [(call-method {:js/str emoji} "codePointAt" {:n 2}) :js/undefined]
   [(call-method {:js/str emoji} "slice" {:n 0} {:n 1}) {:js/units [55357]}]
   [(call-method {:js/str emoji} "slice" {:n 1}) {:js/units [56832]}]
   [(call-method (call-method {:js/str emoji} "slice" {:n 0} {:n 1})
                 "concat"
                 (call-method {:js/str emoji} "slice" {:n 1}))
    {:js/str emoji}]
   [(call-method {:js/str "hello"} "slice" {:n -3}) {:js/str "llo"}]
   [(call-method {:js/str "hello"} "slice" {:n 1} {:n -1}) {:js/str "ell"}]
   [(call-method {:js/str "hello"} "slice" {:n 3} {:n 1}) {:js/str ""}]
   [(call-method {:js/str "hello"} "slice" {:n :-inf} {:n :inf}) {:js/str "hello"}]
   [(call-method {:js/str "hello"} "substring" {:n 3} {:n 1}) {:js/str "el"}]
   [(call-method {:js/str "hello"} "substring" {:n -2} {:n 2}) {:js/str "he"}]
   [(call-method {:js/str "hello"} "substring" {:n :nan} {:n 2}) {:js/str "he"}]
   [(call-method {:js/str "hello"} "substring" {:n 2}) {:js/str "llo"}]
   [(call-method {:js/str "hello"} "indexOf" {:js/str "l"}) {:n 2}]
   [(call-method {:js/str "hello"} "indexOf" {:js/str "l"} {:n 3}) {:n 3}]
   [(call-method {:js/str "hello"} "indexOf" {:js/str "z"}) {:n -1}]
   [(call-method {:js/str "hello"} "indexOf" {:js/str ""} {:n 9}) {:n 5}]
   [(call-method {:js/str "hello"} "at" {:n -1}) {:js/str "o"}]
   [(call-method {:js/str "hello"} "at" {:n 9}) :js/undefined]
   [(call-method {:js/str "ab"} "concat" {:js/str "c"} {:n 1}) {:js/str "abc1"}]
   [(call-method {:n 255} "toString" {:n 16}) {:js/str "ff"}]
   [(call-method {:n 1.5} "toString") {:js/str "1.5"}]
   [(call-method 255 "toString" {:n 2}) {:js/str "11111111"}]
   [(call-method true "toString") {:js/str "true"}]
   [(throws (call-method {:n 1} "toString" {:n 99})) {:js/str "RangeError"}]
   [(throws '(js/get-value :js/undefined {:js/str "x"})) {:js/str "TypeError"}]
   [(throws '(js/get-value :js/null {:js/str "x"})) {:js/str "TypeError"}]
   [(throws (call-method :js/null "slice")) {:js/str "TypeError"}]
   [(throws '(js/call {:n 1} :js/undefined [])) {:js/str "TypeError"}]
   ;; the Error family
   [(list 'js/get-prop (error-ctor "TypeError" {:js/str "m"}) {:js/str "message"})
    {:js/str "m"}]
   [(list 'js/get-prop (error-ctor "TypeError" {:js/str "m"}) {:js/str "name"})
    {:js/str "TypeError"}]
   [(list 'js/get-prop (error-ctor "RangeError") {:js/str "message"})
    {:js/str ""}]
   [(list 'js/to-string (error-ctor "ReferenceError" {:js/str "r"}))
    {:js/str "ReferenceError: r"}]
   [(list 'js/to-string (error-ctor "SyntaxError")) {:js/str "SyntaxError"}]
   [(list 'js/to-string (error-ctor "Error" {:js/str "e"})) {:js/str "Error: e"}]
   [(list 'js/add {:js/str "x"} (error-ctor "Error" {:js/str "e"}))
    {:js/str "xError: e"}]
   [(list 'js/get-prop
          (list 'js/call
                (list 'js/get-prop 'js.rt/global {:js/str "TypeError"})
                :js/undefined
                (av {:js/str "c"}))
          {:js/str "message"})
    {:js/str "c"}]
   [(list 'js/get-prop (error-ctor "Error" {:n 7}) {:js/str "message"})
    {:js/str "7"}]
   ['(js/loose-eq (js/get-prop js.rt/global {:js/str "TypeError"})
                  (js/get-prop js.rt/type-error-proto {:js/str "constructor"}))
    true]
   [(throws '(js/lexical-get (cell/new :js/tdz) {:js/str "x"})) {:js/str "ReferenceError"}]
   ['(js/lexical-get (cell/new 5) {:js/str "x"}) 5]
   [(throws '(js/const-assign!)) {:js/str "TypeError"}]
   [(throws '(js/global-get {:js/str "nope"})) {:js/str "ReferenceError"}]
   ['(js/global-typeof {:js/str "nope"}) {:js/str "undefined"}]
   ['(js/global-typeof {:js/str "String"}) {:js/str "function"}]
   ['(js/global-get {:js/str "undefined"}) :js/undefined]
   ['(js/global-get {:js/str "Infinity"}) {:n :inf}]
   ['(js/typeof (js/global-get {:js/str "console"})) {:js/str "object"}]
   ;; converters
   [(list 'js/call '(js/global-get {:js/str "String"}) :js/undefined (av {:n 1.5}))
    {:js/str "1.5"}]
   [(list 'js/call '(js/global-get {:js/str "String"}) :js/undefined (av '(js/neg {:n 0})))
    {:js/str "0"}]
   ['(js/call (js/global-get {:js/str "String"}) :js/undefined []) {:js/str ""}]
   [(list 'js/call '(js/global-get {:js/str "Number"}) :js/undefined (av {:js/str "12"}))
    {:n 12}]
   [(list 'js/call '(js/global-get {:js/str "Number"}) :js/undefined (av 7)) {:n 7}]
   ['(js/call (js/global-get {:js/str "Number"}) :js/undefined []) {:n 0}]
   [(list 'js/call '(js/global-get {:js/str "Boolean"}) :js/undefined (av {:js/str ""}))
    false]
   [(list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av {:n 5})) 5]
   [(list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av {:js/str " 12 "}))
    12]
   [(list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av {:js/str "0x10"}))
    16]
   [(throws (list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av {:n 1.5})))
    {:js/str "RangeError"}]
   [(throws (list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av {:js/str "x"})))
    {:js/str "SyntaxError"}]
   [(throws (list 'js/call '(js/global-get {:js/str "BigInt"}) :js/undefined (av :js/undefined)))
    {:js/str "TypeError"}]])


(def ^:private function-cases
  "[form expected]: function cells, the calling convention, ordinary
   ToPrimitive, escapes."
  [;; (js/call f this args) with a strict function sees its receiver
   ['(js/call (js/make-function {:js/str "f"} 1 :strict :normal (fn [this args] this))
              {:js/str "r"}
              [])
    {:js/str "r"}]
   ['(js/call (js/make-function {:js/str "f"} 1 :lexical :normal (fn [this args] this))
              {:js/str "r"}
              [])
    :js/undefined]
   [(list 'js/call
          '(js/make-function {:js/str "f"} 2 :strict :normal
                             (fn [this args]
                               (js/add (js/arg args 0) (js/arg args 1))))
          :js/undefined
          (av {:n 1} {:n 2}))
    {:n 3}]
   ['(js/arg [1] 5) :js/undefined]
   ['(js/get-prop (js/make-function {:js/str "f"} 2 :strict :normal (fn [t a] t))
                  {:js/str "name"})
    {:js/str "f"}]
   ['(js/get-prop (js/make-function {:js/str "f"} 2 :strict :normal (fn [t a] t))
                  {:js/str "length"})
    {:n 2}]
   ['(js/typeof (js/make-function {:js/str "f"} 0 :strict :normal (fn [t a] t)))
    {:js/str "function"}]
   ;; construct: a fresh object whose prototype is F.prototype
   ['(let [proto (js/make-object :js/null)
           f (js/make-function {:js/str "F"} 0 :strict :normal
                               (fn [this args]
                                 (do (js/define-own! this
                                                     {:js/str "v"}
                                                     (js/slot (js/arg args 0)
                                                              true
                                                              true
                                                              true))
                                     :js/undefined)))]
       (do (js/define-own! f {:js/str "prototype"} (js/slot proto true false false))
           (js/define-own! proto {:js/str "inherited"} (js/slot {:n 9} true true true))
           (let [o (js/construct f (js/vconj [] {:n 4}))]
             (js/vconj (js/vconj (js/vconj [] (js/get-prop o {:js/str "v"}))
                                 (js/get-prop o {:js/str "inherited"}))
                       (= (get (cell/get o) :proto) proto)))))
    [{:n 4} {:n 9} true]]
   ['(let [other (js/make-object :js/null)
           f (js/make-function {:js/str "F"} 0 :strict :normal (fn [this args] other))]
       (= (js/construct f []) other))
    true]
   [(throws '(js/construct (js/make-function {:js/str "a"} 0 :lexical :normal
                                             (fn [t a] t))
                           []))
    {:js/str "TypeError"}]
   [(throws '(js/construct {:n 1} [])) {:js/str "TypeError"}]
   ;; ordinary ToPrimitive over valueOf and toString
   ['(let [o (js/make-object js.rt/obj-proto)]
       (do (js/define-own! o
                           {:js/str "valueOf"}
                           (js/slot (js/make-function {:js/str "valueOf"} 0 :strict :normal
                                                      (fn [t a] {:n 41}))
                                    true
                                    false
                                    true))
           (js/add o {:n 1})))
    {:n 42}]
   ['(let [o (js/make-object js.rt/obj-proto)]
       (do (js/define-own! o
                           {:js/str "toString"}
                           (js/slot (js/make-function {:js/str "toString"} 0 :strict :normal
                                                      (fn [t a] {:js/str "ts"}))
                                    true
                                    false
                                    true))
           (js/to-string o)))
    {:js/str "ts"}]
   ['(js/to-string (js/make-object js.rt/obj-proto)) {:js/str "[object Object]"}]
   ['(js/loose-eq (js/make-object js.rt/obj-proto) {:js/str "[object Object]"})
    true]
   ['(let [o (js/make-object js.rt/obj-proto)] (js/strict-eq o o)) true]
   ['(js/strict-eq (js/make-object js.rt/obj-proto) (js/make-object js.rt/obj-proto))
    false]
   [(throws '(js/to-string (js/make-object :js/null))) {:js/str "TypeError"}]
   ['(js/to-boolean (js/make-object :js/null)) true]
   ['(js/typeof (js/make-object :js/null)) {:js/str "object"}]
   ;; throw, try and finally
   ['(js/try (fn [] (js/throw {:n 1})) (fn [e] (js/add e {:n 1}))) {:n 2}]
   ['(js/try (fn [] {:n 5}) (fn [e] {:n 6})) {:n 5}]
   ['(js/try (fn [] (js/throw :js/undefined)) (fn [e] e)) :js/undefined]
   ['(js/try (fn [] (js/try (fn [] (js/throw {:n 1})) (fn [e] (js/throw {:n 2}))))
             (fn [e] e))
    {:n 2}]
   ['(let [log (cell/new [])]
       (do (js/try (fn []
                     (js/try-finally
                       (fn [] (js/throw {:n 1}))
                       (fn [x] (cell/set! log (js/vconj (cell/get log) {:n 2})))))
                   (fn [e] (cell/set! log (js/vconj (cell/get log) e))))
           (cell/get log)))
    [{:n 2} {:n 1}]]
   ['(let [log (cell/new [])]
       (do (js/try-finally
             (fn [] (cell/set! log (js/vconj (cell/get log) :body)))
             (fn [x] (cell/set! log (js/vconj (cell/get log) :fin))))
           (cell/get log)))
    [:body :fin]]
   ;; a finally run by an escape
   ['(let [log (cell/new [])]
       (do (js/call-ec
             (fn [k]
               (js/try-finally
                 (fn [] (k 1))
                 (fn [x] (cell/set! log (js/vconj (cell/get log) :fin))))))
           (cell/get log)))
    [:fin]]
   ['(js/call-ec (fn [k] (do (k 7) 8))) 7]
   ['(js/call-ec (fn [k] 8)) 8]
   ['(js/try (fn [] (js/call-ec (fn [k] (js/throw {:n 3})))) (fn [e] e)) {:n 3}]
   ;; an escape out of a try body leaves no handler behind
   ['(let [r (js/call-ec (fn [k] (js/try (fn [] (k 1)) (fn [e] 2))))]
       (js/vconj (js/vconj [] r) (js/handlers)))
    [1 nil]]
   ['(js/try (fn [] (js/call-ec (fn [k] (js/try (fn [] (k 1)) (fn [e] 2)))))
             (fn [e] 3))
    1]])


(defn- objects-and-functions-on-every-vm
  []
  (testing "object reads, primitive receivers, errors, converters"
    (check-cases object-cases))
  (testing "functions, construct, ToPrimitive, throw, try, finally, escapes"
    (check-cases function-cases)))


(deftest ^:slow objects-and-functions-on-every-vm-test
  (slow/guard "objects-and-functions-on-every-vm-test"
              objects-and-functions-on-every-vm))


(deftest ^:slow shared-call-binding-presence-on-every-vm-test
  (doseq [[k result]
          (run-with-prelude
            prelude/uast
            '(let [f (js/make-function {:js/str "arity"} 0 :strict :normal
                                       (fn [this args] (data/count args)))]
               (js/vconj
                 (js/vconj [] (js/call f :js/undefined []))
                 (js/call f :js/undefined [:js/undefined]))))]
    (testing (str k)
      (is (= [0 1] (if (vector? result) result [:thrown result]))))))


(deftest ^:slow shared-call-eligibility-on-every-vm-test
  (doseq [[k result]
          (run-with-prelude
            prelude/uast
            '(let [foreign
                   (cell/new
                     (assoc (assoc {} :py/type :function)
                            :yang.call/entry
                            (fn [invocation]
                              (get invocation :yang.call/callee))))
                   disabled
                   (cell/new
                     (assoc (assoc (assoc {} :py/type :native)
                                   :yang.call/entry (fn [invocation] invocation))
                            :yang.call/eligible? (fn [callee] false)))]
               (js/vconj
                 (js/vconj (js/vconj [] (js/callable? foreign))
                           (= foreign (js/call foreign :js/undefined [])))
                 (js/callable? disabled))))]
    (testing (str k)
      (is (= [true true false] (if (vector? result) result [:thrown result]))))))


(defn- finally-order-on-every-vm
  []
  (testing "nested finally frames run inner first, once each, before the
            outer handler"
    (doseq [[k result]
            (run-with-prelude
              prelude/uast
              '(let [log (cell/new [])]
                 (js/vconj
                   (js/vconj
                     []
                     (js/try (fn []
                               (js/try-finally
                                 (fn []
                                   (js/try-finally
                                     (fn [] (js/throw 1))
                                     (fn [x] (cell/set! log (js/vconj (cell/get log) :a)))))
                                 (fn [x] (cell/set! log (js/vconj (cell/get log) :b)))))
                             (fn [e] e)))
                   (cell/get log))))]
      (testing (str k)
        (is (= [1 [:a :b]] (if (vector? result) result [:thrown result])))))))


(deftest finally-order-on-every-vm-test
  (slow/guard "finally-order-on-every-vm-test" finally-order-on-every-vm))


(defn- init-on-every-vm
  []
  (testing "before js/init! the slot is the literal :js/uninit"
    (doseq [[k result] (run-with-prelude prelude/functions-uast 'js.rt/state)]
      (testing (str k) (is (= :js/uninit result)))))
  (testing "after the bundled prelude ran js/init! once, two more calls
            reallocate nothing and the slot reads ready"
    (doseq [[k result]
            (run-with-prelude
              prelude/uast
              '(let [p1 js.rt/obj-proto
                     f1 js.rt/fn-proto
                     g1 js.rt/global
                     c1 js.rt/ctx
                     o1 js.rt/out
                     e1 js.rt/type-error-proto
                     s1 js.rt/state
                     _ (js/init!)
                     _ (js/init!)]
                 (js/vconj
                   (js/vconj
                     (js/vconj
                       (js/vconj
                         (js/vconj
                           (js/vconj (js/vconj [] (= p1 js.rt/obj-proto))
                                     (= f1 js.rt/fn-proto))
                           (= g1 js.rt/global))
                         (= c1 js.rt/ctx))
                       (= o1 js.rt/out))
                     (= e1 js.rt/type-error-proto))
                   (= s1 :js/ready))))]
      (testing (str k)
        (is (= [true true true true true true true] result)))))
  (testing "the prototype chains of the intrinsics"
    (doseq [[k result]
            (run-with-prelude
              prelude/uast
              '(js/vconj
                 (js/vconj
                   (js/vconj
                     (js/vconj
                       (js/vconj
                         (js/vconj [] (= (get (cell/get js.rt/obj-proto) :proto) :js/null))
                         (= (get (cell/get js.rt/fn-proto) :proto) js.rt/obj-proto))
                       (= (get (cell/get js.rt/str-proto) :proto) js.rt/obj-proto))
                     (= (get (cell/get js.rt/type-error-proto) :proto)
                        js.rt/error-proto))
                   (= (get (cell/get js.rt/error-proto) :proto) js.rt/obj-proto))
                 (js/has-property js.rt/global {:js/str "TypeError"})))]
      (testing (str k)
        (is (= [true true true true true true] result))))))


(deftest init-on-every-vm-test
  (slow/guard "init-on-every-vm-test" init-on-every-vm))


(defn- run-main-on-every-vm
  []
  (testing "run-main collects console.log snapshots and the uncaught throw"
    (doseq [[k result]
            (run-with-prelude
              prelude/uast
              (program-form
                '(js/vconj
                   (js/vconj
                     (js/vconj
                       (js/vconj
                         []
                         (js/run-main
                           (fn [this]
                             (js/log! (js/vconj
                                        (js/vconj
                                          (js/vconj
                                            (js/vconj
                                              (js/vconj
                                                (js/vconj [] {:js/str "a"})
                                                {:n 0.30000000000000004})
                                              5)
                                            :js/null)
                                          (js/neg {:n 0}))
                                        (js/make-function {:js/str "f"} 0 :strict :normal
                                                          (fn [t a] t)))))))
                       (js/run-main
                         (fn [this]
                           (do (js/log! (js/vconj [] {:n 1}))
                               (js/throw (js/make-error js.rt/type-error-proto
                                                        {:js/str "boom"}))))))
                     (js/run-main (fn [this] (js/throw {:js/str "s"}))))
                   (js/run-main (fn [this] (js/throw {:n 7}))))))]
      (testing (str k)
        (is (vector? result))
        (when (vector? result)
          (is (= [{:js/out ["a 0.30000000000000004 5n null -0 [Function: f]"],
                   :js/uncaught nil}
                  {:js/out ["1"], :js/uncaught "TypeError: boom"}
                  {:js/out [], :js/uncaught "s"}
                  {:js/out [], :js/uncaught "7"}]
                 (mapv render/output result))))))))


(deftest run-main-on-every-vm-test
  (slow/guard "run-main-on-every-vm-test" run-main-on-every-vm))
