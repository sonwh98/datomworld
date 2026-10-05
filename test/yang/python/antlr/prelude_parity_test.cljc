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
    [dao.test-slow :as slow]
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


(defn- opts-under
  "The composition over the real modules, `integer` under `limits`."
  [limits]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module
                (integer/register-integer-module limits))})


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


(defn- run-with-prelude
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


(defn- prelude-semantics-on-every-vm
  []
  (let [form (reduce (fn [acc [f _]]
                       (list 'py/conj acc (with-float64 f)))
                     []
                     cases)
        expected (mapv (comp with-float64 second) cases)
        results (run-with-prelude prelude/functions-uast form)]
    (doseq [[k result] results]
      (testing (str k)
        (is (= expected result))))))


(deftest ^:slow prelude-semantics-on-every-vm-test
  (slow/guard "prelude-semantics-on-every-vm-test"
              prelude-semantics-on-every-vm))


(defn- range-fast-path-on-every-host
  []
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


(deftest ^:slow range-fast-path-on-every-host-test
  (slow/guard "range-fast-path-on-every-host-test"
              range-fast-path-on-every-host))


(defn- printed-floats-on-every-host
  []
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


(deftest printed-floats-on-every-host-test
  (slow/guard "printed-floats-on-every-host-test"
              printed-floats-on-every-host))


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


(defn- generator-switch-on-every-host
  []
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


(deftest generator-switch-on-every-host-test
  (slow/guard "generator-switch-on-every-host-test"
              generator-switch-on-every-host))


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


(defn- generator-throw-non-exception-class-on-every-host
  []
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


(deftest generator-throw-non-exception-class-on-every-host-test
  (slow/guard "generator-throw-non-exception-class-on-every-host-test"
              generator-throw-non-exception-class-on-every-host))


(deftest yield-from-and-iter-on-every-host-test
  (testing "yield from sends through to the inner generator, a throw reaches
            the inner's handler, the inner's return value is the
            expression's value and a list delegates through a sequence
            iterator; close runs the inner's finally before the outer's;
            iter of a list is a stateful iterator that is its own iter"
    (let [results (run-with-prelude
                    prelude/uast
                    '(let [note (fn [log x] (cell/set! log (py/conj (cell/get log) x)))
                           inner (fn [log]
                                   (py/make-generator
                                     "inner"
                                     (fn [gen]
                                       (py/try-finally
                                         (fn []
                                           (do (note log (py/yield gen 1))
                                               (py/try (fn [] (py/yield gen 2))
                                                       (fn [e] (py/yield gen :caught))
                                                       (fn [] :py/None))
                                               :r))
                                         (fn [x] (note log :inner-fin))))))
                           outer (fn [log]
                                   (py/make-generator
                                     "outer"
                                     (fn [gen]
                                       (py/try-finally
                                         (fn []
                                           (do (note log (py/yield-from gen (inner log)))
                                               (py/yield-from gen (py/list [10 20]))))
                                         (fn [x] (note log :outer-fin))))))
                           log (cell/new [])
                           o (outer log)
                           a (py/gen-send o :py/None)
                           b (py/gen-send o :s)
                           c (py/gen-throw o py.b/ValueError :py/None :py/None)
                           d (py/gen-send o :py/None)
                           e (py/gen-send o :py/None)
                           f (py/gen-close o)
                           log2 (cell/new [])
                           o2 (outer log2)
                           h (py/gen-send o2 :py/None)
                           i (py/gen-close o2)
                           it (py/iter (py/list [1 2]))
                           j (py/next it :py/missing)
                           k (py/next it :py/missing)
                           l (py/next it :d)
                           m (py/is (py/iter it) it)]
                       (py/conj
                         (py/conj
                           (py/conj
                             (py/conj
                               (py/conj
                                 (py/conj
                                   (py/conj
                                     (py/conj
                                       (py/conj
                                         (py/conj
                                           (py/conj
                                             (py/conj (py/conj (py/conj [] a) b) c)
                                             d)
                                           e)
                                         f)
                                       (cell/get log))
                                     h)
                                   i)
                                 (cell/get log2))
                               j)
                             k)
                           l)
                         m)))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 2 :caught 10 20 :py/None [:s :inner-fin :r :outer-fin]
                  1 :py/None [:inner-fin :outer-fin]
                  1 2 :d true]
                 result)))))))


(defn- delegation-stop-and-sticky-dict-iterator-on-every-host
  []
  (testing "a StopIteration(7) thrown through a closed generator delegate
            completes the delegation with 7, while one escaping the
            delegate's body is still PEP 479's RuntimeError; a dict iterator
            invalidated by a size change keeps raising after the size is
            restored (CPython 3.9.6)"
    (let [results (run-with-prelude
                    prelude/uast
                    (list
                      'let
                      '[outer (fn [i]
                                (py/make-generator
                                  "outer"
                                  (fn [gen] (py/yield gen (py/yield-from gen i)))))
                        i (py/make-generator
                            "inner"
                            (fn [gen] (do (py/yield gen 1) (py/yield gen 2) :py/None)))
                        o (outer i)
                        a (py/gen-send o :py/None)
                        b (py/gen-close i)
                        c (py/gen-throw o py.b/StopIteration 7 :py/None)
                        j (py/make-generator
                            "j"
                            (fn [gen]
                              (do (py/yield gen 1)
                                  (py/raise (py/call py.b/StopIteration [5])))))
                        o2 (outer j)
                        e (py/gen-send o2 :py/None)
                        f (py/try (fn [] (py/gen-send o2 :py/None))
                                  (fn [x] (py/isinstance x py.b/RuntimeError))
                                  (fn [] :no))
                        dct (py/dict-new)
                        s1 (py/dict-set dct {:py/str "a"} 1)
                        s2 (py/dict-set dct {:py/str "b"} 2)
                        before (cell/get dct)
                        it (py/iter dct)
                        g (py/next it :py/missing)
                        s3 (py/dict-set dct {:py/str "c"} 3)
                        h (py/try (fn [] (py/next it :py/missing))
                                  (fn [x] (py/isinstance x py.b/RuntimeError))
                                  (fn [] :no))
                        r (cell/set! dct before)
                        k (py/try (fn [] (py/next it :py/missing))
                                  (fn [x] (py/isinstance x py.b/RuntimeError))
                                  (fn [] :no))]
                      (reduce (fn [acc s] (list 'py/conj acc s)) []
                              '[a b c e f g h k])))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 :py/None 7 1 true {:py/str "a"} true true] result)))))))


(deftest delegation-stop-and-sticky-dict-iterator-on-every-host-test
  (slow/guard "delegation-stop-and-sticky-dict-iterator-on-every-host-test"
              delegation-stop-and-sticky-dict-iterator-on-every-host))


(defn- delegation-chain-callers-and-unwinding-on-every-host
  []
  (testing "a three-deep yield from chain resumed by a changing caller: the
            top level, a caller with its own handler frame, another
            generator, and a delegating generator; then a yield in the
            finally of an inner and an outer generator while a thrown
            exception unwinds through them (CPython 3.9.6)"
    (let [results (run-with-prelude
                    prelude/uast
                    (list
                      'let
                      '[log (cell/new [])
                        note (fn [x] (cell/set! log (py/conj (cell/get log) x)))
                        c (fn []
                            (py/make-generator
                              "c"
                              (fn [gen]
                                (do (note (py/yield gen :c1))
                                    (py/try-finally (fn [] (py/yield gen :c2))
                                                    (fn [x] (note :c-fin)))
                                    :c-ret))))
                        b (fn []
                            (py/make-generator
                              "b"
                              (fn [gen]
                                (do (note (py/yield-from gen (c)))
                                    (py/yield-from gen (py/list [:b-list]))
                                    :b-ret))))
                        a (py/make-generator
                            "a"
                            (fn [gen]
                              (do (note (py/yield-from gen (b)))
                                  (py/yield gen :a-end)
                                  :py/None)))
                        r1 (py/gen-send a :py/None)
                        r2 (py/try-finally (fn [] (py/gen-send a :sent)) (fn [x] :py/None))
                        w (py/make-generator
                            "w"
                            (fn [gen] (py/yield gen (py/next a :py/missing))))
                        r3 (py/gen-send w :py/None)
                        d (py/make-generator "d" (fn [gen] (py/yield-from gen a)))
                        r4 (py/gen-send d :py/None)
                        r5 (py/next d :done)
                        r6 (cell/get log)
                        inner (fn []
                                (py/make-generator
                                  "inner"
                                  (fn [gen]
                                    (py/try-finally
                                      (fn [] (py/yield gen 1))
                                      (fn [x] (py/yield gen :inner-cleanup))))))
                        outer (py/make-generator
                                "outer"
                                (fn [gen]
                                  (py/try-finally
                                    (fn []
                                      (py/try (fn [] (py/yield-from gen (inner)))
                                              (fn [e] (py/yield gen :outer-handler))
                                              (fn [] :py/None)))
                                    (fn [x] (py/yield gen :outer-cleanup)))))
                        u1 (py/gen-send outer :py/None)
                        u2 (py/gen-throw outer py.b/KeyError :py/None :py/None)
                        u3 (py/gen-send outer :py/None)
                        u4 (py/gen-send outer :py/None)
                        u5 (py/next outer :end)
                        outer2 (py/make-generator
                                 "outer2"
                                 (fn [gen]
                                   (py/try-finally
                                     (fn [] (py/yield-from gen (inner)))
                                     (fn [x] (py/yield gen :outer2-cleanup)))))
                        v1 (py/gen-send outer2 :py/None)
                        v2 (py/gen-throw outer2 py.b/ValueError :py/None :py/None)
                        v3 (py/gen-send outer2 :py/None)
                        v4 (py/try (fn [] (py/gen-send outer2 :py/None))
                                   (fn [e] (py/isinstance e py.b/ValueError))
                                   (fn [] :no))]
                      (reduce (fn [acc s] (list 'py/conj acc s)) []
                              '[r1 r2 r3 r4 r5 r6 u1 u2 u3 u4 u5 v1 v2 v3 v4])))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [:c1 :c2 :b-list :a-end :done [:sent :c-fin :c-ret :b-ret]
                  1 :inner-cleanup :outer-handler :outer-cleanup :end
                  1 :inner-cleanup :outer2-cleanup true]
                 result)))))))


(deftest delegation-chain-callers-and-unwinding-on-every-host-test
  (slow/guard "delegation-chain-callers-and-unwinding-on-every-host-test"
              delegation-chain-callers-and-unwinding-on-every-host))


(def ^:private genexp-form
  "A generator expression in the lowering's shape: the source through
   `py/iter` at creation, then an anonymous generator yielding `(f x)` for
   each element, so `f` runs only as the generator is resumed."
  '(fn [src f]
     (let [fst (py/iter src)]
       (py/make-generator "<genexpr>"
                          (fn [gen]
                            (py/for-each fst (fn [x] (py/yield gen (f x)))))))))


(defn- conj-all
  [syms]
  (reduce (fn [acc s] (list 'py/conj acc s)) [] syms))


(defn- generator-expression-on-every-host
  []
  (testing "any, all and sum consume a generator expression lazily: any and
            all stop at the first decisive element; a non-iterable source
            fails at creation, a non-iterable inner clause only when
            resumed; a StopIteration escaping the element is PEP 479's
            RuntimeError and closes it; send ignores the value and close
            closes it (CPython 3.9.6)"
    (let [results (run-with-prelude
                    prelude/uast
                    (list
                      'let
                      ['genexp genexp-form
                       'log '(cell/new [])
                       'note '(fn [x] (do (cell/set! log (py/conj (cell/get log) x)) x))
                       'a '(py/call py.b/any
                                    (py/conj [] (genexp (py/list [1 2 3 4])
                                                        (fn [x] (py/lt 2 (note x))))))
                       'la '(cell/get log)
                       'r1 '(cell/set! log [])
                       'b '(py/call py.b/all
                                    (py/conj [] (genexp (py/list [1 2 3])
                                                        (fn [x] (py/lt (note x) 2)))))
                       'lb '(cell/get log)
                       'c '(py/call py.b/sum
                                    (py/conj [] (genexp (py/range3 1 4 1) (fn [x] x))))
                       'd '(py/try (fn [] (genexp 5 (fn [x] x)))
                                   (fn [e] (py/isinstance e py.b/TypeError))
                                   (fn [] :no))
                       'e '(genexp (py/list [1]) (fn [x] (py/iter 5)))
                       'f '(py/try (fn [] (py/next e :py/missing))
                                   (fn [x] (py/isinstance x py.b/TypeError))
                                   (fn [] :no))
                       'h '(genexp (py/list [1])
                                   (fn [x] (py/next (py/iter (py/list [])) :py/missing)))
                       'i '(py/try (fn [] (py/next h :py/missing))
                                   (fn [x] (py/isinstance x py.b/RuntimeError))
                                   (fn [] :no))
                       'j '(py/next h :closed)
                       'k '(genexp (py/list [1 2 3]) (fn [x] x))
                       'k1 '(py/gen-send k :py/None)
                       'k2 '(py/gen-send k 9)
                       'k3 '(py/gen-close k)
                       'k4 '(get (cell/get k) :state)]
                      (conj-all '[a la b lb c d f i j k1 k2 k3 k4])))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [true [1 2 3] false [1 2] 6 true true true :closed
                  1 2 :py/None :closed]
                 result)))))))


(deftest generator-expression-on-every-host-test
  (slow/guard "generator-expression-on-every-host-test"
              generator-expression-on-every-host))


(defn- generator-expression-admission-on-every-host
  []
  (testing "nested generator expressions cross through py/gen-switch, so
            the recursion-limit admission counts each active one: under
            limit 2 two nested ones run, a third is refused with
            RecursionError and stays created; limit 3 admits it"
    (let [results (run-with-prelude
                    prelude/uast
                    (list
                      'let
                      ['genexp genexp-form
                       'ident '(fn [x] x)
                       's1 '(cell/set! py.rt/limit 2)
                       'a '(genexp (genexp (py/list [1 2]) ident) ident)
                       'x1 '(py/next a :py/missing)
                       'inner '(genexp (py/list [7]) ident)
                       'c '(genexp (genexp inner ident) ident)
                       'x2 '(py/try (fn [] (py/next c :py/missing))
                                    (fn [e] (py/isinstance e py.b/RecursionError))
                                    (fn [] :no))
                       'x3 '(get (cell/get inner) :state)
                       'x4 '(py/next a :py/missing)
                       's2 '(cell/set! py.rt/limit 3)
                       'd '(genexp (genexp (genexp (py/list [5]) ident) ident) ident)
                       'x5 '(py/next d :py/missing)]
                      (conj-all '[x1 x2 x3 x4 x5])))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [1 true :created 2 5] result)))))))


(deftest generator-expression-admission-on-every-host-test
  (slow/guard "generator-expression-admission-on-every-host-test"
              generator-expression-admission-on-every-host))


(defn- integer-bound-on-every-host
  []
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


(deftest integer-bound-on-every-host-test
  (slow/guard "integer-bound-on-every-host-test"
              integer-bound-on-every-host))


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
  "2^1074 in hex, the denominator of the smallest subnormal."
  (apply str "4" (repeat 268 "0")))


(def ^:private max-finite
  "The numerator of the largest finite binary64, (2^53 - 1) * 2^971, in
   hex."
  (apply str "fffffffffffff8" (repeat 242 "0")))


(def ^:private two-1022
  "2^1022 in hex, the denominator of the smallest normal binary64."
  (apply str "4" (repeat 255 "0")))


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
   ['(py/key 10) [:py.numeric/finite "a" "1"]]
   [(list 'py/key two-53) [:py.numeric/finite "20000000000000" "1"]]
   ['(py/key {:py/float 1.5}) [:py.numeric/finite "3" "2"]]
   ['(py/key {:py/float -0.75}) [:py.numeric/finite "-3" "4"]]
   ['(py/key {:py/float 0.1})
    [:py.numeric/finite "ccccccccccccd" "80000000000000"]]
   ['(py/key {:py/float 5.0E-324}) [:py.numeric/finite "1" two-1074]]
   ['(py/key (py/float (* (data/float-value 1.5) 4503599627370496 4503599627370496)))
    [:py.numeric/finite "180000000000000000000000000" "1"]]
   [(list 'py/key two-53+1) [:py.numeric/finite "20000000000001" "1"]]
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
    [:py.numeric/finite "fffffffffffff" two-1074]]
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


(defn- numeric-keys-hash-and-is-on-every-host
  []
  (let [form (reduce (fn [acc [f _]] (list 'py/conj acc (with-float64 f)))
                     []
                     key-hash-is-cases)
        expected (mapv second key-hash-is-cases)
        results (run-with-prelude prelude/functions-uast form)]
    (doseq [[k result] results]
      (testing (str k)
        (is (= expected result))))))


(deftest ^:slow numeric-keys-hash-and-is-on-every-host-test
  (slow/guard "numeric-keys-hash-and-is-on-every-host-test"
              numeric-keys-hash-and-is-on-every-host))


(def ^:private is-table
  "`[name form]` of the values `float-is-on-every-host-test` compares:
   a zero of each sign, NaNs by two computations and by decoding Jing's
   canonical NaN, equal floats, integers and bools, infinities, 2^53+1 by
   two computations, and tuples holding them."
  (let [decoded (cbor/decode (jing/canonical-bytes (cbor/float64 ##NaN)))]
    [['zero {:py/float 0.0}]
     ['neg-zero '(py/float (* (data/float-value -1) (data/float-value 0)))]
     ['nan '(py/float (- (data/float-value ##Inf) (data/float-value ##Inf)))]
     ['nan2 '(py/float (* (data/float-value 0) (data/float-value ##Inf)))]
     ['nan3 {:py/float decoded}]
     ['one-f {:py/float 1.0}]
     ['one-f2 '(py/float (/ (data/float-value 3) (data/float-value 3)))]
     ['one 1]
     ['yes true]
     ['inf {:py/float ##Inf}]
     ['ninf {:py/float ##-Inf}]
     ['big two-53+1]
     ['big2 (list 'integer/add two-53 1)]
     ['t-nan '(py/tuple (py/conj [] nan))]
     ['t-nan2 '(py/tuple (py/conj [] nan2))]
     ['t-zero '(py/tuple (py/conj [] zero))]
     ['t-neg-zero '(py/tuple (py/conj [] neg-zero))]]))


(defn- float-is-on-every-host
  []
  (testing "`is` is Jing content identity (yang.antlr.md 8.5.4): every NaN
            is one value, 0.0 is not -0.0 while == holds, 1 is not 1.0,
            True is not 1, tuples recurse; is-not negates; and over every
            pair of the table, is answers exactly whether the canonical
            bytes agree, computed on each host"
    (let [names (mapv first is-table)
          pairs (for [a names, b names] [a b])
          conj-all (fn [xs]
                     (reduce (fn [acc x] (list 'py/conj acc x)) [] xs))
          form (with-float64
                 (list 'let (vec (mapcat identity is-table))
                       (conj-all
                         [(conj-all names)
                          (conj-all (map (fn [[a b]] (list 'py/is a b))
                                         pairs))
                          (conj-all (map (fn [[a b]] (list 'py/is-not a b))
                                         pairs))
                          '(py/eq zero neg-zero)
                          '(let [x nan] (py/is x x))])))
          at (fn [m a b] (get m [a b]))
          bytes-of (fn [v] (jing/bytes->base64 (jing/canonical-bytes v)))]
      (doseq [[k result] (run-with-prelude prelude/functions-uast form)]
        (testing (str k)
          (is (and (vector? result) (not= :thrown (first result)))
              (pr-str result))
          (when (vector? result)
            (let [[values is-row is-not-row eq-zero same-binding] result
                  is? (zipmap pairs is-row)
                  value (zipmap names values)]
              (is (= false (at is? 'zero 'neg-zero)))
              (is (= true eq-zero))
              (is (= true (at is? 'nan 'nan2)))
              (is (= true (at is? 'nan 'nan3)))
              (is (= true same-binding))
              (is (= true (at is? 'one-f 'one-f2)))
              (is (= false (at is? 'one 'one-f)))
              (is (= false (at is? 'yes 'one)))
              (is (= true (at is? 'inf 'inf)))
              (is (= false (at is? 'inf 'ninf)))
              (is (= true (at is? 'big 'big2)))
              (is (= true (at is? 't-nan 't-nan2)))
              (is (= false (at is? 't-zero 't-neg-zero)))
              (is (= (map not is-row) is-not-row))
              (is (= (map (fn [[a b]]
                            (= (bytes-of (value a)) (bytes-of (value b))))
                          pairs)
                     is-row)))))))))


(deftest ^:slow float-is-on-every-host-test
  (slow/guard "float-is-on-every-host-test"
              float-is-on-every-host))


(def ^:private caught
  "Prelude notation: `[class-name & args]` of the guest exception
   `thunk` raises, else its value."
  '(fn [thunk]
     (py/try thunk
             (fn [e]
               (data/into (py/conj []
                                   (get (cell/get (get (cell/get e) :class))
                                        :name))
                          (get (get (get (cell/get e) :attrs) "args") :items)))
             (fn [] :py/None))))


(defn- big-integer-keys-past-the-digit-limit-on-every-host
  []
  (testing "2^20000 has 6021 decimal digits, past max-digits 4300, yet keys
            in hex: it differs from 2^20000 + 1, and dict insertion,
            lookup, replacement and deletion, set membership and tuple keys
            all work with it"
    (let [results (run-with-prelude
                    prelude/uast
                    '(let [big (integer/shift-left 1 20000)
                           big1 (integer/add big 1)
                           k (py/key big)
                           d (py/dict-new)
                           _1 (py/dict-set d big {:py/str "a"})
                           _2 (py/dict-set d big1 {:py/str "b"})
                           _3 (py/dict-set d (integer/shift-left 1 20000)
                                           {:py/str "c"})
                           n1 (data/count (get (cell/get d) :keys))
                           v (py/getitem d big)
                           _4 (py/dict-del-quiet d big)
                           n2 (data/count (get (cell/get d) :keys))
                           s (py/set-from (py/conj (py/conj [] big) big1))
                           t (py/dict-new)
                           _5 (py/dict-set t (py/tuple (py/conj [1] big)) 7)]
                       (py/conj
                         (py/conj
                           (py/conj
                             (py/conj
                               (py/conj
                                 (py/conj
                                   (py/conj
                                     (py/conj
                                       (py/conj (py/conj [] k)
                                                (= k (py/key big1)))
                                       n1)
                                     v)
                                   n2)
                                 (py/contains d big))
                               (py/getitem d big1))
                             (data/count (get (cell/get s) :keys)))
                           (py/contains s big))
                         (py/getitem t (py/tuple
                                         (py/conj [1]
                                                  (integer/shift-left
                                                    1 20000)))))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [[:py.numeric/finite (apply str "1" (repeat 5000 "0")) "1"]
                  false 2 {:py/str "c"} 1 false {:py/str "b"} 2 true 7]
                 result))))))
  (testing "its decimal text is still the digit limit: the module answers
            the reason, and py/int-result raises it as a ValueError"
    (doseq [[k result] (run-with-prelude
                         prelude/uast
                         (list 'let ['caught caught]
                               '(let [big (integer/shift-left 1 20000)]
                                  (py/conj
                                    (py/conj [] (integer/format big))
                                    (caught
                                      (fn []
                                        (py/int-result
                                          (integer/format big))))))))]
      (is (= [::integer/digit-limit
              ["ValueError"
               {:py/str "Exceeds the limit for integer string conversion"}]]
             result)
          (str k)))))


(deftest ^:slow big-integer-keys-past-the-digit-limit-on-every-host-test
  (slow/guard "big-integer-keys-past-the-digit-limit-on-every-host-test"
              big-integer-keys-past-the-digit-limit-on-every-host))


;; =============================================================================
;; C3 slice S3a: integer limit reasons are guest exceptions
;; =============================================================================
;;
;; Under `small-runners` (60 bits, 5 digits) the smallest subnormal's key
;; needs 2^1074, a float 2^80's key needs 2^80, and every hash needs P =
;; 2^61 - 1: each is a module `::bit-limit`, which the prelude raises as
;; MemoryError; decimal text past 5 digits is `::digit-limit`, raised as
;; ValueError. The guest sees only the prelude's class and message.

(def ^:private tiny
  "5e-324, whose key needs a 1075-bit denominator."
  {:py/float 5.0E-324})


(defn- int-limits-are-catchable-on-every-host
  []
  (testing "MemoryError from keys and hashes, ValueError from decimal
            text: caught by py/try, the run continues, finally runs, and
            MemoryError is an Exception"
    (let [f2-80 '(py/float (* (data/float-value 1) 4503599627370496 268435456))
          results (run-with-prelude
                    small-runners
                    prelude/uast
                    (with-float64
                      (list
                        'let
                        ['caught caught
                         'tiny tiny
                         'log '(cell/new [])
                         'fin '(caught
                                 (fn []
                                   (py/try-finally
                                     (fn [] (py/key tiny))
                                     (fn [x]
                                       (cell/set! log
                                                  (conj (cell/get log)
                                                        :finally))))))
                         'exc '(py/try (fn [] (py/hash 1))
                                       (fn [e] e)
                                       (fn [] :py/None))]
                        (conj-all
                          ['(caught (fn [] (py/key tiny)))
                           (list 'caught (list 'fn [] (list 'py/key f2-80)))
                           '(caught (fn [] (py/hash 1)))
                           '(caught (fn [] (py/hash {:py/float 1.5})))
                           '(caught
                              (fn [] (py/int-result (integer/format 123456))))
                           '(py/key {:py/float 1.5})
                           'fin
                           '(cell/get log)
                           '(py/isinstance exc py.b/Exception)
                           '(py/exc-matches exc py.b/Exception)
                           '(py/add 1 2)]))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [["MemoryError"] ["MemoryError"] ["MemoryError"]
                  ["MemoryError"]
                  ["ValueError"
                   {:py/str "Exceeds the limit for integer string conversion"}]
                  [:py.numeric/finite "3" "2"]
                  ["MemoryError"] [:finally] true true 3]
                 result)))))))


(deftest ^:slow int-limits-are-catchable-on-every-host-test
  (slow/guard "int-limits-are-catchable-on-every-host-test"
              int-limits-are-catchable-on-every-host))


(defn- failed-key-normalization-leaves-containers-unchanged
  []
  (testing "a MemoryError from key normalization writes nothing: dict
            insert, lookup, membership, delete and a tuple key, and set add"
    (let [results (run-with-prelude
                    small-runners
                    prelude/uast
                    (with-float64
                      (list
                        'let
                        ['caught caught
                         'tiny tiny
                         'd '(py/dict-new)
                         '_1 '(py/dict-set d 1 {:py/str "a"})
                         'd0 '(cell/get d)
                         's '(py/set-from [1 2])
                         's0 '(cell/get s)
                         'r (conj-all
                              ['(caught
                                  (fn [] (py/dict-set d tiny {:py/str "b"})))
                               '(caught (fn [] (py/getitem d tiny)))
                               '(caught (fn [] (py/contains d tiny)))
                               '(caught (fn [] (py/dict-del-quiet d tiny)))
                               '(caught
                                  (fn []
                                    (py/dict-set d
                                                 (py/tuple (py/conj [1] tiny))
                                                 {:py/str "c"})))
                               '(caught (fn [] (py/set-add s tiny)))])]
                        (conj-all ['r '(= d0 (cell/get d))
                                   '(= s0 (cell/get s))]))))]
      (doseq [[k result] results]
        (testing (str k)
          (is (= [(vec (repeat 6 ["MemoryError"])) true true] result)))))))


(deftest ^:slow failed-key-normalization-leaves-containers-unchanged-test
  (slow/guard "failed-key-normalization-leaves-containers-unchanged-test"
              failed-key-normalization-leaves-containers-unchanged))


(defn- int-defects-stay-host-failures-on-every-host
  []
  (testing "a wrong type or arity at an integer call, and a reason the
            module version 2 never returns, fail the run: no guest handler
            sees them"
    (doseq [[form message]
            [['(integer/neg {:py/str "x"}) integer/refusal-message]
             ['(integer/neg 1 2) integer/refusal-message]
             ['(py/int-result :yin.vm.integer/zero-division)
              "Cannot apply non-function"]
             ['(py/int-result :yin.vm.integer/wrong-type)
              "Cannot apply non-function"]]]
      (doseq [[k result] (run-with-prelude
                           small-runners
                           prelude/uast
                           (list 'py/try
                                 (list 'fn [] (list 'py/int-result form))
                                 '(fn [e] :caught)
                                 '(fn [] :py/None)))]
        (is (= [:thrown message] result) (str k " " (pr-str form)))))))


(deftest ^:slow int-defects-stay-host-failures-on-every-host-test
  (slow/guard "int-defects-stay-host-failures-on-every-host-test"
              int-defects-stay-host-failures-on-every-host))


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


(defn- preserved-key-arms-on-every-host
  []
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


(deftest preserved-key-arms-on-every-host-test
  (slow/guard "preserved-key-arms-on-every-host-test"
              preserved-key-arms-on-every-host))


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


(defn- unhashable-on-every-host
  []
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


(deftest unhashable-on-every-host-test
  (slow/guard "unhashable-on-every-host-test"
              unhashable-on-every-host))


(defn- signed-zero-floats-on-every-host
  []
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


(deftest signed-zero-floats-on-every-host-test
  (slow/guard "signed-zero-floats-on-every-host-test"
              signed-zero-floats-on-every-host))


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
