(ns yang.cross-lang-test
  "The substrate claim, empirically (yang.antlr.md 8.10/8.11): both preludes
   bottom out in `:application`, and the engine neither knows nor cares which
   frontend minted a closure. A TypeScript function value is called from
   PHP-lowered code and a PHP closure from TypeScript-lowered code, in one
   VM, one run.

   The seam is the lowered tree, not a prelude change. Each direction lowers
   a program normally, then (1) extracts the foreign function value by
   running the donor program with its `run-main` replaced so the program's
   value IS the function (`js/make-function` cell, `php/make-function` cell),
   and (2) binds that value to a free variable of the receiver, replacing the
   receiver's lookup node (`php/resolve-fn` of a name; `js/global-get` of a
   name) by that variable. Both function values are cells whose `:code` is
   called with a receiver slot and an argument vector, so the callee needs no
   knowledge of the caller's prelude.

   What is pinned as LIMITS (documented expectations, not failures):
   a PHP array reaching TypeScript is an opaque handle that only PHP can use;
   PHP null (`:php/null`) is neither JS null nor undefined. The JVM lane runs
   the AST walker only."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]
    [yang.php.antlr.e2e-harness :as php]
    [yang.php.antlr.portable-harness :as php-h]
    [yang.typescript.antlr.e2e-test :as ts]
    [yang.typescript.antlr.render :as render]
    [yin.vm :as vm]
    [yin.vm.test-utils :as tu]))


(defn- app?
  [node op]
  (and (map? node)
       (= :application (:type node))
       (= {:type :variable, :name op} (:operator node))))


(defn- find-node
  "The first node of `tree` satisfying `pred`, preorder."
  [pred tree]
  (let [hit (volatile! nil)]
    (walk/prewalk (fn [n] (when (and (nil? @hit) (pred n)) (vreset! hit n)) n)
                  tree)
    @hit))


(defn- var-node
  [name]
  {:type :variable, :name name})


(defn- donor
  "The AST whose value is the function value of the donor program: the
   `run-main` application becomes a call of its body, which is replaced by
   the first function-making node (`make-fn`) of the program."
  [ast run-main make-fn body-args]
  (let [fn-node (find-node #(app? % make-fn) ast)]
    (assert fn-node (str "no " make-fn " node in the donor"))
    (walk/prewalk
      (fn [n]
        (if (app? n run-main)
          {:type :application,
           :operator {:type :lambda,
                      :params ['%body],
                      :body {:type :application,
                             :operator (var-node '%body),
                             :operands body-args}},
           :operands [{:type :lambda,
                       :params (if (= make-fn 'js/make-function) ['%0] []),
                       :body fn-node}]}
          n))
      ast)))


(defn- lowered-php
  [source]
  (let [{:keys [ast diagnostics]} (php/lower-program source)]
    (assert (nil? diagnostics) (pr-str diagnostics))
    ast))


(defn- lowered-ts
  [source]
  (let [r (ts/lower-source source :bundled)]
    (assert (nil? (:diagnostics r)) (pr-str (:diagnostics r)))
    (let [{:yin/keys [batch root]} (:program r)]
      (nth batch root))))


(defn- bind
  "`receiver` with `v-name` bound to the value of `donor-ast`."
  [donor-ast v-name receiver]
  {:type :application,
   :operator {:type :lambda, :params [v-name], :body receiver},
   :operands [donor-ast]})


(defn- registry
  []
  (php-h/host-registry))


(defn- run
  [ast]
  (vm/value (vm/eval (tu/create-vm {:modules (registry)}) ast)))


(defn ts-into-php
  "PHP `source` calling `ts_fn(...)`, with `ts_fn` the TS function `ts-src`
   (a program of one arrow expression). Returns the printed text."
  [ts-src source]
  (let [fn-ast (donor (lowered-ts ts-src) 'js/run-main 'js/make-function
                      [{:type :literal, :value :js/undefined}])
        php-ast (walk/postwalk
                  (fn [n]
                    (if (app? n 'php/resolve-fn)
                      (var-node '%tsfn)
                      n))
                  (lowered-php source))]
    (php-h/render (run (bind fn-ast '%tsfn php-ast)))))


;; `js/call` admits only a cell whose `:js/type` is `:function`, so the PHP
;; function cell cannot be called by TS bare. The adapter is a test-local
;; `js/make-function` node (no prelude change) whose code forwards the
;; argument vector to the PHP cell's own `:code`.
(defn- js-shaped
  [cell-var]
  (letfn [(lit [v] {:type :literal, :value v})
          (app
            [op & args]
            {:type :application, :operator (var-node op), :operands (vec args)})]
    (app 'js/make-function
         (lit {:js/str "phpfn"}) (lit 0) (lit :lexical) (lit :arrow)
         {:type :lambda,
          :params ['%t '%a],
          :body {:type :application,
                 :operator (app 'get (app 'cell/get (var-node cell-var)) (lit :code)),
                 :operands [(var-node cell-var) (var-node '%a)]}})))


(defn php-into-ts
  "TS `source` calling `phpfn(...)`, with `phpfn` the PHP closure `php-src`
   (a program holding one closure expression). Returns the rendered value."
  [php-src source]
  (let [fn-ast (donor (lowered-php php-src) 'php/run-main 'php/make-function [])
        ts-ast (walk/postwalk
                 (fn [n]
                   (if (and (app? n 'js/global-get)
                            (= "phpfn" (get-in n [:operands 0 :value :js/str])))
                     (js-shaped '%phpfn)
                     n))
                 (lowered-ts source))]
    (render/output (run (bind fn-ast '%phpfn ts-ast)))))


(deftest ts-closure-called-from-php-test
  (testing "a number crosses as a number: float in, float out"
    (is (= "4"
           (ts-into-php "(x, y) => x + y;" "<?php echo ts_fn(1.5, 2.5);"))))
  (testing "the PHP caller sees the result as an ordinary value"
    (is (= "yes"
           (ts-into-php "(x, y) => x + y;"
                        "<?php echo ts_fn(1.5, 2.5) == 4 ? 'yes' : 'no';")))))


(deftest php-closure-called-from-ts-test
  (is (= {:js/out ["5"], :js/uncaught nil}
         (php-into-ts "<?php fn($a, $b) => $a + $b;"
                      "console.log(phpfn(2, 3));"))))


(deftest limits-test
  (testing "a PHP array returned to TS is an opaque handle, usable in PHP"
    (let [src "<?php fn($a = 7) => $a === 7 ? [1, 2, 3] : count($a);"]
      ;; the PHP int `count` returns reaches TS as an integer (`3n`), not a
      ;; JS number: the two preludes keep their own number kinds
      (is (= {:js/out ["3n"], :js/uncaught nil}
             (php-into-ts src "const h = phpfn(); console.log(phpfn(h));")))))
  (testing "PHP null is neither JS null nor undefined"
    (let [src "<?php fn() => null;"]
      (is (= {:js/out ["false false"], :js/uncaught nil}
             (php-into-ts src
                          "const n = phpfn(); console.log(n === null, n === undefined);"))))))
