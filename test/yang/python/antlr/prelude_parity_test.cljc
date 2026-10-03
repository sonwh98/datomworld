(ns yang.python.antlr.prelude-parity-test
  "The prelude's Python semantics on all four VMs and every host this runs
   on (JVM and Node): arithmetic with bools, float tagging, truthiness,
   cross-type equality, dict key normalization, numeric hash and integer
   `is` (C3 slice S2), ranges, and a run through
   `py/run-module` over the real cell and data modules whose printed output
   is rendered at the boundary."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]
    [dao.jing :as jing]
    [dao.jing.cbor :as cbor]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
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


(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (integer/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300}))})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
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


(defn- run-with-prelude
  "The value of `form` (prelude notation) after `prelude-ast`."
  [prelude-ast form]
  (let [ast (u/mark-tails (u/then prelude-ast (u/sexp->uast form)))]
    (into {}
          (map (fn [[k run]]
                 [k (try (run ast)
                         (catch #?(:cljd Object :clj Exception :cljs :default) e
                           [:thrown (ex-message e)]))]))
          runners)))


(defn- with-float64
  "`form` with every `{:py/float x}` payload as float64 content, as the
   lowering and the prelude build it (on JS, Jing's carrier: a bare 1.0
   there is the integer 1)."
  [form]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (contains? x :py/float))
                     (update x :py/float data/float64)
                     x))
                 form))


(def ^:private cases
  "[form expected], evaluated as one vector on each VM."
  '[[(py/add 1 {:py/float 2.5}) {:py/float 3.5}]
    [(py/add true 1) 2]
    [(py/sub 5 true) 4]
    [(py/mul 3 4) 12]
    [(py/mul 3 {:py/float 1.5}) {:py/float 4.5}]
    [(py/truediv 7 2) {:py/float 3.5}]
    [(py/float? (py/truediv 4 2)) true]
    [(py/float? (py/add 2 2)) false]
    [(py/neg 3) -3]
    [(py/neg {:py/float 0.5}) {:py/float -0.5}]
    [(py/truthy 0) false]
    [(py/truthy {:py/float 0.0}) false]
    [(py/truthy {:py/str ""}) false]
    [(py/truthy :py/None) false]
    [(py/truthy 5) true]
    [(py/truthy {:py/str "a"}) true]
    [(py/not 0) true]
    [(py/eq 1 {:py/float 1.0}) true]
    [(py/eq {:py/float 1.5} {:py/float 1.5}) true]
    [(py/eq 1 true) true]
    [(py/eq {:py/str "a"} {:py/str "a"}) true]
    [(py/eq 1 {:py/str "1"}) false]
    [(py/eq :py/None :py/None) true]
    [(py/ne 2 3) true]
    [(py/lt 1 2) true]
    [(py/ge 2 {:py/float 2.0}) true]
    [(py/lt {:py/float 2.5} 2) false]
    [(py/is :py/None :py/None) true]
    [(= (py/key 1) (py/key {:py/float 1.0})) true]
    [(= (py/key true) (py/key 1)) true]
    [(= (py/key {:py/str "k"}) {:py/str "k"}) true]
    [(py/numeric? {:py/str "1"}) false]
    [(py/numeric? :py/None) false]
    [(py/range-at (py/range3 0 10 3) 2) 6]
    [(py/range-len (py/range3 10 0 -3) 0) 4]
    [(py/iter-at (py/range3 0 2 1) 2) :py/stop]
    [(py/floordiv -7 2) -4]
    [(py/floordiv 7 -2) -4]
    [(py/mod -7 3) 2]
    [(py/mod 7 -3) -2]
    [(py/floordiv 1000000007 97) 10309278]
    [(py/floordiv {:py/float 7.5} 2) {:py/float 3.0}]
    [(py/mod {:py/float -7.5} 2) {:py/float 0.5}]
    [(py/floor -2.5) -3]
    [(py/floor 2.0) 2]
    [(py/pow 2 10) 1024]
    [(py/pow 2 -1) {:py/float 0.5}]
    [(py/pow -2 3) -8]
    [(py/bitand -6 3) 2]
    [(py/bitor 6 3) 7]
    [(py/bitxor 6 3) 5]
    [(py/bitand -1 -8) -8]
    [(py/invert 5) -6]
    [(py/lshift 1 4) 16]
    [(py/rshift -16 2) -4]
    [(py/eq (py/tuple [1 2]) (py/tuple [{:py/float 1.0} 2])) true]
    [(= (py/key (py/tuple [1 2])) (py/key (py/tuple [{:py/float 1.0} 2]))) true]
    [(py/slice-positions (py/slice :py/None :py/None -1) 5) [4 3 2 1 0]]
    [(py/slice-positions (py/slice -2 100 :py/None) 5) [3 4]]
    [(py/slice-positions (py/slice 1 :py/None 2) 6) [1 3 5]]
    [(py/pow 2 53) 9007199254740992]
    [(py/lshift 1 52) 4503599627370496]
    [(py/lshift 1 53) 9007199254740992]
    [(py/add 9007199254740991 1) 9007199254740992]
    [(py/sub -9007199254740991 1) -9007199254740992]
    [(py/mul 4503599627370496 2) 9007199254740992]
    [(py/mul 94906265 94906265) 9007199136250225]
    [(py/rshift -1 100) -1]
    [(py/mod {:py/float 5.9} {:py/float 1.1}) {:py/float 0.3999999999999999}]
    [(py/mod {:py/float -5.9} {:py/float 1.1}) {:py/float 0.7000000000000002}]
    [(py/floordiv {:py/float 7.5} -2) {:py/float -4.0}]
    [(py/range-len (py/range3 0 4503599627370496 3) 0) 1501199875790166]
    [(py/range-has? (py/range3 10 0 -3) 7) true]
    [(py/range-has? (py/range3 10 0 -3) 5) false]
    [(py/to-vector (py/range3 -9007199254740992 9007199254740992 6004799503160661))
     [-9007199254740992 -3002399751580331 3002399751580330 9007199254740991]]
    [(py/to-vector (py/range3 9007199254740992 -9007199254740992 -6004799503160661))
     [9007199254740992 3002399751580331 -3002399751580330 -9007199254740991]]
    [(py/range-len (py/range3 -9007199254740992 9007199254740992 9007199254740992) 0) 2]
    [(py/range-len (py/range3 -9007199254740992 9007199254740992 6004799503160661) 0) 4]
    [(py/range-has? (py/range3 -9007199254740992 9007199254740992 6004799503160661)
                    9007199254740991)
     true]
    [(py/range-has? (py/range3 -9007199254740992 9007199254740992 9007199254740992) 1)
     false]
    [(py/range-elem -9007199254740992 6004799503160661 3) 9007199254740991]
    ;; the O(1) path's guard, 0 <= i <= 2^26, |step| <= 2^26, |start| <=
    ;; 2^52: each edge, and one past it through range-elem
    [(py/range-at (py/range3 0 9007199254740992 67108864) 67108864) 4503599627370496]
    [(py/range-at (py/range3 0 9007199254740992 67108864) 67108865) 4503599694479360]
    [(py/range-at (py/range3 0 9007199254740992 67108865) 67108864) 4503599694479360]
    [(py/range-at (py/range3 0 -9007199254740992 -67108864) 67108864) -4503599627370496]
    [(py/range-at (py/range3 0 -9007199254740992 -67108864) 67108865) -4503599694479360]
    [(py/range-at (py/range3 0 -9007199254740992 -67108865) 67108864) -4503599694479360]
    [(py/range-at (py/range3 4503599627370496 9007199254740992 67108864) 67108863)
     9007199187632128]
    [(py/range-at (py/range3 4503599627370496 9007199254740992 67108864) 67108864) :py/stop]
    [(py/range-at (py/range3 -4503599627370496 -9007199254740992 -67108864) 67108863)
     -9007199187632128]
    [(py/range-at (py/range3 -4503599627370496 -9007199254740992 -67108864) 67108864)
     :py/stop]
    [(py/range-at (py/range3 4503599627370497 9007199254740992 67108864) 67108863)
     9007199187632129]
    [(py/range-at (py/range3 4503599627370497 9007199254740992 67108864) 67108864) :py/stop]
    [(py/range-at (py/range3 -4503599627370497 -9007199254740992 -67108864) 67108863)
     -9007199187632129]
    [(py/range-at (py/range3 -4503599627370497 -9007199254740992 -67108864) 67108864)
     :py/stop]
    [(py/to-vector (py/range3 -5 5 1)) [-5 -4 -3 -2 -1 0 1 2 3 4]]
    [(py/to-vector (py/range3 5 -5 -3)) [5 2 -1 -4]]
    [(py/float-mod 1.0E20 3.0) 1.0]
    [(py/float-mod -1.0E20 3.0) 2.0]
    [(py/float-mod 1.0E300 7.0) 1.0]
    [(py/snapshot {:py/str "s"}) "s"]
    [(py/snapshot :py/None) nil]])


(deftest prelude-semantics-on-every-vm-test
  (let [form (reduce (fn [acc [f _]]
                       (list 'py/conj acc (with-float64 f)))
                     []
                     cases)
        expected (mapv (comp with-float64 second) cases)
        results (run-with-prelude prelude/functions-uast form)]
    (doseq [[k result] results]
      (testing (str k)
        (is (= expected result))))))


(deftest range-fast-path-on-every-host-test
  (testing "iterating range(3000) never enters the recursive range-elem:
            with it stubbed to a sentinel, every element is still exact. The
            guard's edge is pinned too: index 2^26 takes the O(1) path,
            2^26 + 1 enters range-elem, so a guard widened past the ruled
            bound fails here. The sentinel is a number no element of these
            ranges equals, so range-at's bound test reads it on every host"
    (let [stubbed (u/seq-nodes
                    (map (fn [[k form]]
                           (u/def! k
                                   (u/sexp->uast
                                     (if (= k 'py/range-elem)
                                       '(fn [start step i] -12345)
                                       form))))
                         prelude/function-definitions))
          results (run-with-prelude
                    stubbed
                    '(py/conj
                       (py/conj
                         (py/conj (py/conj [] (py/range-elem 0 1 5))
                                  (py/to-vector (py/range3 0 3000 1)))
                         (py/range-at (py/range3 0 67108865 1) 67108864))
                       (py/range-at (py/range3 0 67108866 1) 67108865)))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [-12345 (vec (range 3000)) 67108864 -12345] result)))))))


(deftest printed-floats-on-every-host-test
  (testing "over the real cell and data modules: print(2, 4/2, 1 + 2.0, 0.5)
            renders 2 2.0 3.0 0.5 on every VM and host, JS included"
    (let [results (run-with-prelude
                    prelude/uast
                    (with-float64
                      '(py/run-module
                         (fn [g]
                           (py/print
                             (py/conj (py/conj (py/conj (py/conj [] 2)
                                                        (py/truediv 4 2))
                                               (py/add 1 {:py/float 2.0}))
                                      {:py/float 0.5}))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= {:py/out ["2 2.0 3.0 0.5"], :py/exception nil}
                 (if (map? result) (render/output result) result))))))))


(deftest escapes-on-every-host-test
  (testing "py/call-ec and py/try tell a first pass from a re-entry with a
            flag cell, never by inspecting the continuation"
    (let [results (run-with-prelude
                    prelude/uast
                    '(py/run-module
                       (fn [g]
                         (py/print
                           (py/conj (py/conj [] (py/call-ec (fn [k] (do (k 7) 8))))
                                    (py/try (fn []
                                              (py/raise-new py.b/ValueError
                                                            {:py/str "v"}))
                                            (fn [e] (py/isinstance e py.b/ValueError))
                                            (fn [] :no)))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= {:py/out ["7 True"], :py/exception nil}
                 (if (map? result) (render/output result) result))))))))


(deftest generator-switch-on-every-host-test
  (testing "a generator built from a literal body, switched three times:
            two yields, then completion, never a raise from its side"
    (let [results (run-with-prelude
                    prelude/uast
                    '(let [g (py/make-generator
                               "g"
                               (fn [gen] (do (py/yield gen 1) (py/yield gen 2) :py/None)))]
                       (py/conj (py/conj (py/conj [] (py/gen-switch g [:send :py/None]))
                                         (py/gen-switch g [:send :py/None]))
                                (py/gen-switch g [:send :py/None]))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [[:yield 1] [:yield 2] [:return :py/None]] result)))))))


(deftest generator-throw-and-close-on-every-host-test
  (testing "a throw caught at the yield site yields again; close runs the
            finally around the yield and leaves the generator closed; a
            generator that yields after GeneratorExit makes close a
            RuntimeError and stays suspended"
    (let [results (run-with-prelude
                    prelude/uast
                    (list
                      'let
                      '[log (cell/new [])
                        g (py/make-generator
                            "g"
                            (fn [gen]
                              (py/try-finally
                                (fn []
                                  (do (py/try (fn [] (py/yield gen 1))
                                              (fn [e] (py/yield gen :caught))
                                              (fn [] :py/None))
                                      (py/yield gen 2)))
                                (fn [x]
                                  (cell/set! log
                                             (py/conj (cell/get log) :fin))))))
                        h (py/make-generator
                            "h"
                            (fn [gen]
                              (py/try (fn [] (py/yield gen 1))
                                      (fn [e] (py/yield gen :ignored))
                                      (fn [] :py/None))))
                        a (py/gen-send g :py/None)
                        b (py/gen-throw g py.b/ValueError :py/None :py/None)
                        c (py/gen-send g :py/None)
                        d (py/gen-close g)
                        e (cell/get log)
                        f (py/gen-switch g [:send :py/None])
                        i (py/gen-send h :py/None)
                        j (py/try (fn [] (py/gen-close h))
                                  (fn [x] (py/isinstance x py.b/RuntimeError))
                                  (fn [] :no))
                        k (get (cell/get h) :state)]
                      (reduce (fn [acc s] (list 'py/conj acc s)) []
                              '[a b c d e f i j k])))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 :caught 2 :py/None [:fin] [:return :py/None]
                  1 true :suspended]
                 result)))))))


(deftest generator-throw-non-exception-class-on-every-host-test
  (testing "throwing a class not deriving from BaseException is a TypeError
            before the class is called: its __init__ never runs and the
            generator stays suspended at its yield"
    (let [results (run-with-prelude
                    prelude/uast
                    '(let [log (cell/new [])
                           c (py/make-class "C" py.b/object)
                           s (py/setattr
                               c "__init__"
                               (py/make-function
                                 "__init__" {:params ["self" "v"], :no-kw true}
                                 [] []
                                 (fn [args]
                                   (cell/set! log (py/conj (cell/get log)
                                                           (py/arg args 1))))))
                           g (py/make-generator
                               "g"
                               (fn [gen]
                                 (do (py/yield gen 1)
                                     (py/yield gen 2)
                                     :py/None)))
                           a (py/gen-send g :py/None)
                           b (py/try (fn [] (py/gen-throw g c 7 :py/None))
                                     (fn [x] (py/isinstance x py.b/TypeError))
                                     (fn [] :no))
                           d (cell/get log)
                           e (py/gen-send g :py/None)]
                       (py/conj (py/conj (py/conj (py/conj [] a) b) d) e)))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 true [] 2] result)))))))


(deftest integer-bound-on-every-host-test
  (testing "over the real cell and data modules, results outside
            [-2^53, 2^53] are a guest OverflowError identically on every VM
            and host (the JVM would otherwise throw, JS round, Dart wrap):
            2**53+1, 3**40, 1<<54, -(2**53)-1, (2**53-1)*3"
    (let [ov '(fn [thunk]
                (let [r (cell/new :py/None)]
                  (do (py/try (fn [] (cell/set! r (thunk)))
                              (fn [e] (cell/set! r {:py/str "overflow"}))
                              (fn [] :py/None))
                      (cell/get r))))
          results (run-with-prelude
                    prelude/uast
                    (list 'py/run-module
                          (list 'fn '[g gf]
                                (list 'let ['ov ov]
                                      '(py/print
                                         (py/conj
                                           (py/conj
                                             (py/conj
                                               (py/conj
                                                 (py/conj
                                                   (py/conj [] (py/pow 2 53))
                                                   (ov (fn [] (py/add (py/pow 2 53) 1))))
                                                 (ov (fn [] (py/pow 3 40))))
                                               (ov (fn [] (py/lshift 1 54))))
                                             (ov (fn [] (py/sub (py/neg (py/pow 2 53)) 1))))
                                           (ov (fn [] (py/mul 9007199254740991 3)))))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= {:py/out ["9007199254740992 overflow overflow overflow overflow overflow"],
                  :py/exception nil}
                 (if (map? result) (render/output result) result))))))))


;; =============================================================================
;; C3 slice S2: numeric keys, guest hash, integer `is`
;; =============================================================================
;;
;; Big integers come from `integer/parse` over decimal text, never a
;; literal: on JS an integer literal past 2^53 - 1 cannot be hashed into
;; rows, and neither can an integral float literal there, so big floats are
;; built by multiplication. Hashes are compared as `integer/format` text,
;; so one expectation holds whatever carrier each host uses; the values are
;; CPython's on a 64-bit build.

(def ^:private two-53 '(integer/parse "9007199254740992"))
(def ^:private two-53+1 '(integer/parse "9007199254740993"))
(def ^:private two-70 '(integer/parse "1180591620717411303424"))
(def ^:private two-80 '(integer/parse "1208925819614629174706176"))


(def ^:private two-1074
  "2^1074, the denominator of the smallest subnormal."
  (str "2024022533073106183524953467189173070495566497641421183569013580274303"
       "3956799534689196038370143712449518707786431681191138980873738579347686"
       "7013399940738509921517424276566361364466907742093216341239767678472745"
       "0685620074834246926986181033556491595563408100565123587695523334146152"
       "30502532186327508646006263307707741093494784"))


(def ^:private max-finite
  "The numerator of the largest finite binary64, (2^53 - 1) * 2^971."
  (str "179769313486231570814527423731704356798070567525844996598917476803"
       "157260780028538760589558632766878171540458953514382464234321326889"
       "464182768467546703537516986049910576551282076245490090389328944075"
       "868508455133942304583236903222948165808559332123348274797826204144"
       "723168738177180919299881250404026184124858368"))


(def ^:private two-1022
  "2^1022, the denominator of the smallest normal binary64."
  (str "449423283715578976932326297697256183404494244735576643183575202894"
       "331689513752407831771193306018840052800284699678483394146974422036"
       "041556232118576598685310944419733562163713190755549003115235298632"
       "707380212514422095376705856157203684782776352068092908376276711465"
       "74559986811484619929076208839082406056034304"))


(defn- hash-text
  [x]
  (list 'integer/format (list 'py/hash x)))


(def ^:private key-hash-is-cases
  "[form expected], evaluated as one vector on each VM."
  [;; ruling 6: one exact key form; bools key as their integers
   ['(py/key 1) [:py.numeric/finite "1" "1"]]
   ['(py/key true) [:py.numeric/finite "1" "1"]]
   ['(py/key {:py/float 1.0}) [:py.numeric/finite "1" "1"]]
   ['(py/key false) [:py.numeric/finite "0" "1"]]
   ['(py/key (py/float (* (data/float-value -1) (data/float-value 0)))) [:py.numeric/finite "0" "1"]]
   ['(py/key -7) [:py.numeric/finite "-7" "1"]]
   ['(py/key {:py/float 1.5}) [:py.numeric/finite "3" "2"]]
   ['(py/key {:py/float -0.75}) [:py.numeric/finite "-3" "4"]]
   ['(py/key {:py/float 0.1})
    [:py.numeric/finite "3602879701896397" "36028797018963968"]]
   ['(py/key {:py/float 5.0E-324}) [:py.numeric/finite "1" two-1074]]
   ['(py/key (py/float (* (data/float-value 1.5) 4503599627370496 4503599627370496)))
    [:py.numeric/finite "30423614405477505635920876929024" "1"]]
   [(list 'py/key two-53+1) [:py.numeric/finite "9007199254740993" "1"]]
   [(list '= (list 'py/key two-53) (list 'py/key two-53+1)) false]
   [(list '= (list 'py/key two-53) '(py/key (py/float (* (data/float-value 2) 4503599627370496)))) true]
   [(list '= (list 'py/key two-80) '(py/key (py/float (* (data/float-value 1) 4503599627370496 268435456))))
    true]
   ;; binary64 boundaries: the largest finite, the negative smallest
   ;; subnormal, and both sides of the normal/subnormal transition
   ['(py/key {:py/float 1.7976931348623157E308}) [:py.numeric/finite max-finite "1"]]
   ['(py/key {:py/float -4.9E-324}) [:py.numeric/finite "-1" two-1074]]
   ['(py/key {:py/float 2.2250738585072014E-308}) [:py.numeric/finite "1" two-1022]]
   ['(py/key {:py/float 2.225073858507201E-308})
    [:py.numeric/finite "4503599627370495" two-1074]]
   ;; an integer -0 (on JS, (* -1 0)) keys and hashes as the integer 0
   ['(py/key (* -1 0)) [:py.numeric/finite "0" "1"]]
   ['(py/key {:py/float ##Inf}) [:py.numeric/infinite "+"]]
   ['(py/key {:py/float ##-Inf}) [:py.numeric/infinite "-"]]
   ;; every NaN is one key (yang.antlr.md 8.5.4), never an infinity's
   ['(py/key (py/float (- (data/float-value ##Inf) (data/float-value ##Inf)))) [:py.numeric/nan]]
   ['(= (py/key (py/float (- (data/float-value ##Inf) (data/float-value ##Inf)))) (py/key (py/float (* (data/float-value 0) (data/float-value ##Inf))))) true]
   ['(= (py/key (py/float (- (data/float-value ##Inf) (data/float-value ##Inf)))) (py/key {:py/float ##Inf})) false]
   [(list '= (list 'py/key (list 'py/tuple (list 'py/conj [1] two-53+1)))
          (list 'py/key (list 'py/tuple (list 'py/conj [{:py/float 1.0}] two-53+1))))
    true]
   ['(py/key {:py/str "k"}) {:py/str "k"}]
   ['(= (py/key -1) (py/key -2)) false]
   ;; ruling 7: P = 2^61 - 1, equal numbers hash equal, -1 is -2
   [(hash-text 0) "0"]
   [(hash-text false) "0"]
   [(hash-text {:py/float 0.0}) "0"]
   [(hash-text '(py/float (* (data/float-value -1) (data/float-value 0)))) "0"]
   [(hash-text 1) "1"]
   [(hash-text true) "1"]
   [(hash-text {:py/float 1.0}) "1"]
   [(hash-text 12345) "12345"]
   [(hash-text -1) "-2"]
   [(hash-text -2) "-2"]
   [(hash-text two-70) "512"]
   [(hash-text two-80) "524288"]
   [(hash-text (list 'integer/neg two-80)) "-524288"]
   [(hash-text '(py/float (* (data/float-value 1) 4503599627370496 268435456))) "524288"]
   [(hash-text '(integer/sub (integer/shift-left 1 61) 1)) "0"]
   [(hash-text '(integer/shift-left 1 61)) "1"]
   [(hash-text '(integer/neg (integer/shift-left 1 61))) "-2"]
   [(hash-text {:py/float 1.5}) "1152921504606846977"]
   [(hash-text {:py/float -0.5}) "-1152921504606846976"]
   [(hash-text {:py/float 0.1}) "230584300921369408"]
   [(hash-text {:py/float 5.0E-324}) "16777216"]
   [(hash-text {:py/float -2.5E-300}) "-52920977297143526"]
   [(hash-text '(py/float (* (data/float-value 1.5) 4503599627370496 4503599627370496))) "13194139533312"]
   [(hash-text '(integer/parse "30423614405477505635920876929024")) "13194139533312"]
   [(hash-text {:py/float 1.7976931348623157E308}) "2234066890152476671"]
   [(hash-text {:py/float -4.9E-324}) "-16777216"]
   [(hash-text {:py/float 2.2250738585072014E-308}) "32768"]
   [(hash-text {:py/float 2.225073858507201E-308}) "2305843009196949503"]
   [(hash-text '(* -1 0)) "0"]
   ;; ruling 8: value-based `is`, whatever carrier a value arrived in
   [(list 'py/is two-70 two-70) true]
   [(list 'py/is two-70 '(integer/pow 2 70)) true]
   [(list 'py/is-not two-70 '(integer/pow 2 70)) false]
   [(list 'py/is (list 'integer/sub (list 'integer/add two-70 5) two-70) 5) true]
   [(list 'py/is (list 'integer/sub two-53+1 1) two-53) true]
   [(list 'py/is (list 'integer/sub two-53 1) 9007199254740991) true]
   [(list 'py/is (list 'py/hash two-80) 524288) true]
   [(list 'py/is two-53 two-53+1) false]
   ['(py/is true 1) false]
   ['(py/is 1 {:py/float 1.0}) false]])


(deftest numeric-keys-hash-and-is-on-every-host-test
  (let [form (reduce (fn [acc [f _]] (list 'py/conj acc (with-float64 f)))
                     []
                     key-hash-is-cases)
        expected (mapv second key-hash-is-cases)
        results (run-with-prelude prelude/functions-uast form)]
    (doseq [[k result] results]
      (testing (str k)
        (is (= expected result))))))


(deftest numeric-dict-keys-on-every-host-test
  (testing "one slot per numeric value, the first inserted key kept, the
            latest value stored: {1: 'a', 1.0: 'b', True: 'c'}; 0.0, -0.0,
            0 and False; 2^53 and 2^53 + 1 apart, 2^53 and float(2^53)
            together; 2^80 and float(2^80) together; inf and -inf apart;
            two NaNs together"
    (let [fill (fn [pairs]
                 (list 'let ['d '(py/dict-new)]
                       (list 'do
                             (cons 'do (map (fn [[k v]] (list 'py/dict-set 'd k {:py/str v}))
                                            pairs))
                             '(let [c (cell/get d)]
                                (py/conj (py/conj [] (get c :keys)) (get c :vals))))))
          f2-53 '(py/float (* (data/float-value 2) 4503599627370496))
          f2-80 '(py/float (* (data/float-value 1) 4503599627370496 268435456))
          forms [(fill [[1 "a"] [{:py/float 1.0} "b"] [true "c"]])
                 (fill [[{:py/float 0.0} "a"] ['(py/float (* (data/float-value -1) (data/float-value 0))) "b"]
                        [0 "c"] [false "d"]])
                 (fill [[two-53 "a"] [two-53+1 "b"] [f2-53 "c"]])
                 (fill [[two-80 "a"] [f2-80 "b"]])
                 (fill [[{:py/float ##Inf} "a"] [{:py/float ##-Inf} "b"]
                        [{:py/float ##Inf} "c"]])
                 (fill [[-1 "a"] [-2 "b"]])
                 (fill [['(py/float (- (data/float-value ##Inf) (data/float-value ##Inf))) "a"] ['(py/float (* (data/float-value 0) (data/float-value ##Inf))) "b"]])]
          results (run-with-prelude
                    prelude/functions-uast
                    (with-float64
                      (list 'let ['shown '(fn [kv]
                                            (py/conj (py/conj [] (data/count (get kv 0)))
                                                     (get kv 1)))
                                  'first-key '(fn [kv] (get (get kv 0) 0))]
                            (list 'py/conj
                                  (list 'py/conj
                                        (reduce (fn [acc f]
                                                  (list 'py/conj acc
                                                        (list 'shown f)))
                                                []
                                                forms)
                                        (list 'first-key (nth forms 0)))
                                  (list 'integer/format
                                        (list 'first-key (nth forms 3)))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [[1 [{:py/str "c"}]]
                  [1 [{:py/str "d"}]]
                  [2 [{:py/str "c"} {:py/str "b"}]]
                  [1 [{:py/str "b"}]]
                  [2 [{:py/str "c"} {:py/str "b"}]]
                  [2 [{:py/str "a"} {:py/str "b"}]]
                  [1 [{:py/str "b"}]]
                  1
                  "1208925819614629174706176"]
                 result)))))))


(def ^:private class-name-of
  "Prelude notation: the guest class name of what `thunk` raises."
  '(fn [thunk]
     (py/try thunk
             (fn [e] (get (cell/get (get (cell/get e) :class)) :name))
             (fn [] :py/None))))


(deftest preserved-key-arms-on-every-host-test
  (testing "through py/key, by dict and set insertion: identity objects
            (cells) key by identity and stay distinct; lists, dicts, sets
            and tuples holding one are unhashable; numeric set dedup keeps
            the first original key (True before 1 stays True)"
    (let [results (run-with-prelude
                    prelude/uast
                    (with-float64
                      (list
                        'let
                        ['err class-name-of
                         'a '(py/make-class "A" py.b/object)
                         'b '(py/make-class "B" py.b/object)
                         'g '(py/make-generator "g" (fn [gen] :py/None))
                         'd '(py/dict-new)
                         '_1 '(py/dict-set d a 1)
                         '_2 '(py/dict-set d b 2)
                         '_3 '(py/dict-set d g 3)
                         '_4 '(py/dict-set d py.b/len 4)
                         '_5 '(py/dict-set d a 5)
                         's1 '(py/set-from [1 {:py/float 1.0} true 2])
                         's2 '(py/set-from [true 1 {:py/float 1.0}])]
                        '(py/conj
                           (py/conj
                             (py/conj
                               (py/conj
                                 (py/conj
                                   (py/conj
                                     (py/conj
                                       (py/conj
                                         (py/conj
                                           (py/conj [] (data/count (get (cell/get d) :keys)))
                                           (py/getitem d a))
                                         (py/getitem d g))
                                       (get (cell/get s1) :keys))
                                     (get (cell/get s2) :keys))
                                   (err (fn [] (py/dict-set d (py/list []) 1))))
                                 (err (fn [] (py/dict-set d (py/dict-new) 1))))
                               (err (fn [] (py/set-add (py/set-new) (py/set-new)))))
                             (err (fn [] (py/dict-set d (py/tuple (py/conj [1] (py/list []))) 1))))
                           (err (fn []
                                  (py/set-add (py/set-new)
                                              (py/tuple (py/conj [] (py/tuple (py/conj [] (py/dict-new))))))))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [4 5 3 [1 2] [true]
                  "TypeError" "TypeError" "TypeError" "TypeError" "TypeError"]
                 result)))))))


(deftest nan-keys-on-every-host-test
  (testing "every NaN is one dict/set key, recursively inside tuple keys
            (the architects' NaN ruling, yang.antlr.md 8.5.4). A \"same
            object\" case is not testable: a float here is a value with no
            object identity, so lookup through x is lookup through an equal
            value. y and z are NaNs produced independently of x: by 0 * inf,
            and by decoding Jing's canonical float64 NaN"
    (let [decoded (cbor/decode (jing/canonical-bytes (cbor/float64 ##NaN)))
          results (run-with-prelude
                    prelude/uast
                    (with-float64
                      (list
                        'let
                        ['x '(py/float (- (data/float-value ##Inf)
                                          (data/float-value ##Inf)))
                         'y '(py/float (* (data/float-value 0)
                                          (data/float-value ##Inf)))
                         'z {:py/float decoded}
                         'd '(py/dict-new)
                         '_1 '(py/dict-set d x 1)
                         'a '(py/getitem d x)
                         'b '(py/getitem d y)
                         'c '(py/getitem d z)
                         '_2 '(py/dict-set d y 2)
                         'n '(data/count (get (cell/get d) :keys))
                         'v '(py/getitem d x)
                         's '(py/set-from (py/conj (py/conj [] x) y))
                         't '(py/dict-new)
                         '_3 '(py/dict-set t (py/tuple (py/conj [1] x)) :found)]
                        '(py/conj
                           (py/conj
                             (py/conj
                               (py/conj
                                 (py/conj
                                   (py/conj
                                     (py/conj
                                       (py/conj
                                         (py/conj (py/conj (py/conj [] a) b) c)
                                         n)
                                       v)
                                     (data/count (get (cell/get s) :keys)))
                                   (py/contains s x))
                                 (py/contains s y))
                               (py/contains s z))
                             (py/getitem t (py/tuple (py/conj [1] y))))
                           (py/getitem t (py/tuple (py/conj [{:py/float 1.0}] z)))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 1 1 1 2 1 true true true :found :found] result)))))))


(deftest unhashable-on-every-host-test
  (testing "hash() of a list or any other cell is a guest TypeError; of a
            non-numeric value it is not yet supported"
    (let [caught '(fn [thunk]
                    (let [r (cell/new :py/None)]
                      (do (py/try (fn [] (cell/set! r (thunk)))
                                  (fn [e]
                                    (cell/set! r (get (cell/get (get (cell/get e) :class))
                                                      :name)))
                                  (fn [] :py/None))
                          (cell/get r))))
          results (run-with-prelude
                    prelude/uast
                    (list 'py/run-module
                          (list 'fn '[g gf]
                                (list 'let ['caught caught]
                                      '(py/print
                                         (py/conj
                                           (py/conj
                                             (py/conj
                                               (py/conj [] (caught (fn [] (py/hash (py/list [])))))
                                               (caught (fn [] (py/hash (py/dict-new)))))
                                             (caught (fn [] (py/hash py.b/len))))
                                           (caught (fn [] (py/hash {:py/str "s"})))))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= {:py/out ["TypeError TypeError TypeError NotImplementedError"],
                  :py/exception nil}
                 (if (map? result) (render/output result) result))))))))


(deftest signed-zero-floats-on-every-host-test
  (testing "float % and // on exact multiples and infinite divisors, rendered
            on the host because = cannot see a zero's sign: the JVM (where
            host = tells 0.0 from 0) and Node (where it cannot) agree with
            CPython"
    (let [forms '[(py/float-mod 4.0 -2.0)
                  (py/float-mod -4.0 2.0)
                  (py/float-mod -4.0 -2.0)
                  (get (py/float-divmod 4.0 -2.0) 0)
                  (get (py/float-divmod -4.0 2.0) 0)
                  (get (py/float-divmod 4.0 -2.0) 1)
                  (py/float-mod 0.0 ##Inf)
                  (py/float-mod 0.0 ##-Inf)
                  (py/float-mod 5.0 ##Inf)
                  (py/float-mod -5.0 ##Inf)
                  (py/float-mod 5.0 ##-Inf)
                  (get (py/float-divmod -5.0 ##Inf) 0)
                  (get (py/float-divmod 0.0 ##-Inf) 0)
                  (get (py/float-divmod 5.0 ##Inf) 0)
                  (get (py/float-divmod 0.0 ##Inf) 1)
                  (get (py/neg {:py/float 0.0}) :py/float)]
          expected ["-0.0" "0.0" "-0.0" "-2.0" "-2.0" "-0.0"
                    "0.0" "-0.0" "5.0" "inf" "-inf"
                    "-1.0" "-0.0" "0.0" "0.0" "-0.0"]
          results (run-with-prelude prelude/functions-uast
                                    (reduce (fn [acc f]
                                              (list 'py/conj acc (with-float64 f)))
                                            []
                                            forms))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= expected
                 (if (vector? result) (mapv render/float-repr result) result))))))))


(deftest render-test
  (is (= "2.0" (render/float-repr 2.0)))
  (is (= "-0.0" (render/float-repr -0.0)))
  (is (= "0.5" (render/float-repr 0.5)))
  (is (= "inf" (render/float-repr ##Inf)))
  (is (= "[1, 'a', 2.5, None, (1,)]"
         (render/repr [1 "a" {:py/float 2.5} nil {:py/tuple [1]}]))))


(deftest prelude-notation-test
  (testing "the prelude is canonical Universal AST: no reserved-name defect,
            and every definition key is a literal symbol"
    (is (nil? (vm/ast-reserved-defect prelude/uast)))
    (is (every? symbol? (map first prelude/function-definitions))))
  (testing "sexp->uast"
    (is (= (u/app (u/lam ['x] (u/if-node (u/v 'x) (u/lit 1) (u/lit {:a 1})))
                  u/capture)
           (u/sexp->uast '(let [x (%capture)] (if x 1 {:a 1})))))))
