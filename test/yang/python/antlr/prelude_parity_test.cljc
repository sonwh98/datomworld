(ns yang.python.antlr.prelude-parity-test
  "The prelude's Python semantics on all four VMs and every host this runs
   on (JVM and Node): arithmetic with bools, float tagging, truthiness,
   cross-type equality, dict key normalization, ranges, and a run through
   `py/run-module` over the real cell and data modules whose printed output
   is rendered at the boundary."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
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
                data/register-data-module)})


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
    [(py/snapshot {:py/str "s"}) "s"]
    [(py/snapshot :py/None) nil]])


(deftest prelude-semantics-on-every-vm-test
  (let [form (reduce (fn [acc [f _]] (list 'py/conj acc f)) [] cases)
        expected (mapv second cases)
        results (run-with-prelude prelude/functions-uast form)]
    (doseq [[k result] results]
      (testing (str k)
        (is (= expected result))))))


(deftest printed-floats-on-every-host-test
  (testing "over the real cell and data modules: print(2, 4/2, 1 + 2.0, 0.5)
            renders 2 2.0 3.0 0.5 on every VM and host, JS included"
    (let [results (run-with-prelude
                    prelude/uast
                    '(py/run-module
                       (fn [g]
                         (py/print (py/conj (py/conj (py/conj (py/conj [] 2)
                                                              (py/truediv 4 2))
                                                     (py/add 1 {:py/float 2.0}))
                                            {:py/float 0.5})))))]
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
