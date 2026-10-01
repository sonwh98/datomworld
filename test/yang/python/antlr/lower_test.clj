(ns yang.python.antlr.lower-test
  "Lowering goldens: the Universal AST shape of representative programs,
   qualified refusals, every grammar rule classified, and chunk invariance
   through both stages."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.uast :as u]
    [yin.vm :as vm]
    [yin.vm.test-utils :as tu])
  (:import
    (yang.python.antlr.gen
      Python3Parser)))


(defn- body
  [src]
  (lower/lower-module-body (parser/parse-source src)))


(defn- id-of
  "The CST id of the first node of `rule` in `src`'s packet."
  [src rule]
  (:id (first (filter #(= rule (:rule %))
                      (:yang.cst/nodes (parser/parse-source src))))))


(defn- sym
  [prefix src rule]
  (symbol (str "%" prefix (id-of src rule))))


(def ^:private none (u/lit :py/None))


(defn- py
  [nm & args]
  (apply u/app (u/v nm) args))


(defn- local
  [nm]
  (py 'py/local-get (u/v (symbol nm)) (u/lit {:py/str nm})))


(defn- gget
  [nm]
  (py 'py/global-get (u/v '%globals) (u/lit {:py/str nm})))


(defn- gset
  [nm v]
  (py 'py/global-set (u/v '%globals) (u/lit {:py/str nm}) v))


(defn- call
  [f & args]
  (py 'py/call f (reduce #(py 'py/conj %1 %2) (u/lit []) args)))


(defn- builtin
  "A builtin name read: the module binding if present, else the builtin."
  [nm]
  (py 'py/global-or (u/v '%globals) (u/lit {:py/str nm}) (u/v (symbol "py.b" nm))))


(defn- fobj
  "A function object with no defaults."
  [nm nparams star? args code-body]
  (py 'py/make-function (u/lit nm) (u/lit nparams) (u/lit []) (u/lit star?)
      (u/lam [args] code-body)))


;; =============================================================================
;; Goldens
;; =============================================================================

(deftest module-assignment-golden-test
  (testing "module names go through the namespace dict, not the store"
    (is (= (u/then (gset "x" (u/lit 1)) none)
           (body "x = 1\n")))))


(deftest literals-golden-test
  (testing "floats are tagged, ints are not; print is an ordinary call"
    (is (= (u/then (call (builtin "print")
                         (u/lit 255) (u/lit {:py/float 1.5}) (u/lit {:py/str "a\nb"})
                         (u/lit {:py/str "raw\\n"}) none (u/lit true))
                   none)
           (body "print(0xff, 1.5, 'a\\nb', r'raw\\n', None, True)\n")))))


(deftest function-golden-test
  (testing "a function object whose code takes one argument vector;
            parameters are rebound to cells, other locals start unbound,
            the body runs under a return escape, reads go through
            py/local-get and writes through cell/set!"
    (let [src "def f(a):\n    b = a\n    return b\n"
          ret (sym "ret" src "funcdef")
          args (sym "args" src "funcdef")]
      (is (= (u/then
               (gset "f"
                     (fobj "f" 1 false args
                           (u/app (u/lam '[a b]
                                         (py 'py/call-ec
                                             (u/lam [ret]
                                                    (u/seq-nodes
                                                      [(py 'cell/set! (u/v 'b) (local "a"))
                                                       (u/app (u/v ret) (local "b"))
                                                       none]))))
                                  (py 'cell/new (py 'py/arg (u/v args) (u/lit 0)))
                                  (py 'cell/new (u/lit :py/unbound)))))
               none)
             (body src))))))


(deftest function-without-return-golden-test
  (testing "no return statement, no escape; no locals, no cell frame"
    (let [src "def g():\n    print()\n"
          args (sym "args" src "funcdef")]
      (is (= (u/then (gset "g" (fobj "g" 0 false args
                                     (u/then (call (builtin "print")) none)))
                     none)
             (body src))))))


(deftest defaults-and-star-golden-test
  (testing "defaults are evaluated at definition time in the enclosing scope;
            *rest is the last parameter and nparams excludes it"
    (let [src "def f(a, b=k, *rest):\n    pass\n"
          args (sym "args" src "funcdef")]
      (is (= (u/then
               (gset "f"
                     (py 'py/make-function (u/lit "f") (u/lit 2)
                         (py 'py/conj (u/lit []) (gget "k"))
                         (u/lit true)
                         (u/lam [args]
                                (u/app (u/lam '[a b rest] (u/then none none))
                                       (py 'cell/new (py 'py/arg (u/v args) (u/lit 0)))
                                       (py 'cell/new (py 'py/arg (u/v args) (u/lit 1)))
                                       (py 'cell/new (py 'py/arg (u/v args) (u/lit 2)))))))
               none)
             (body src))))))


(deftest while-golden-test
  (let [src "while x:\n    break\n"
        brk (sym "brk" src "while_stmt")
        w (sym "w" src "while_stmt")]
    (is (= (u/then
             (py 'py/call-ec
                 (u/lam [brk]
                        (u/let1 w
                                (u/lam [w]
                                       (u/if-node (py 'py/truthy (gget "x"))
                                                  (u/then (u/app (u/v brk) none)
                                                          (u/app (u/v w) (u/v w)))
                                                  none))
                                (u/app (u/v w) (u/v w)))))
             none)
           (body src)))))


(deftest for-golden-test
  (let [src "for k in xs:\n    continue\n"
        [cont w it i x] (map #(sym % src "for_stmt") ["cont" "w" "it" "i" "x"])]
    (is (= (u/then
             (u/let1 it
                     (gget "xs")
                     (u/let1 w
                             (u/lam [w i]
                                    (u/let1 x
                                            (py 'py/iter-at (u/v it) (u/v i))
                                            (u/if-node
                                              (py '= (u/v x) (u/lit :py/stop))
                                              none
                                              (u/then (gset "k" (u/v x))
                                                      (u/then (py 'py/call-ec
                                                                  (u/lam [cont]
                                                                         (u/app (u/v cont) none)))
                                                              (u/app (u/v w) (u/v w)
                                                                     (py '+ (u/v i) (u/lit 1))))))))
                             (u/app (u/v w) (u/v w) (u/lit 0))))
             none)
           (body src)))))


(deftest try-golden-test
  (let [src "try:\n    f()\nexcept E as e:\n    raise\nexcept:\n    pass\n"
        exc (sym "exc" src "try_stmt")]
    (is (= (u/then
             (py 'py/try
                 (u/lam [] (call (gget "f")))
                 (u/lam [exc]
                        (u/if-node (py 'py/isinstance (u/v exc) (gget "E"))
                                   (u/then (gset "e" (u/v exc))
                                           (py 'py/raise (u/v exc)))
                                   none))
                 (u/lam [] none))
             none)
           (body src)))))


(deftest boolean-and-comparison-golden-test
  (let [src "a or b\nx < y < z\n"
        t (sym "t" src "and_test")
        pk (parser/parse-source src)
        chain (first (filter #(and (= "comparison" (:rule %))
                                   (< 1 (count (:children %))))
                             (:yang.cst/nodes pk)))
        [c0 c1 c2] (map #(symbol (str "%c" %))
                        (filter #(= "expr" (:rule (nth (:yang.cst/nodes pk) %)))
                                (:children chain)))]
    (is (= (u/seq-nodes
             [(u/let1 t (gget "a")
                      (u/if-node (py 'py/truthy (u/v t)) (u/v t) (gget "b")))
              (u/let1 c0 (gget "x")
                      (u/let1 c1 (gget "y")
                              (u/if-node (py 'py/lt (u/v c0) (u/v c1))
                                         (u/let1 c2 (gget "z")
                                                 (py 'py/lt (u/v c1) (u/v c2)))
                                         (u/lit false))))
              none])
           (body src)))))


(deftest class-golden-test
  (let [src "class C(B):\n    k = 1\n    def m(self):\n        return self.k\n"
        cls (sym "cls" src "classdef")
        ret (sym "ret" src "funcdef")
        args (sym "args" src "funcdef")]
    (is (= (u/then
             (u/let1 cls
                     (py 'py/make-class (u/lit "C") (gget "B"))
                     (u/seq-nodes
                       [(py 'py/setattr (u/v cls) (u/lit "k") (u/lit 1))
                        (py 'py/setattr (u/v cls) (u/lit "m")
                            (fobj "m" 1 false args
                                  (u/app (u/lam '[self]
                                                (py 'py/call-ec
                                                    (u/lam [ret]
                                                           (u/then (u/app (u/v ret)
                                                                          (py 'py/getattr (local "self")
                                                                              (u/lit "k")))
                                                                   none))))
                                         (py 'cell/new (py 'py/arg (u/v args) (u/lit 0))))))
                        (gset "C" (u/v cls))]))
             none)
           (body src)))))


(deftest assignment-order-golden-test
  (testing "the value is evaluated once, before any target, and targets are
            assigned left to right"
    (let [src "a[i] = b = v\n"
          tmp (sym "v" src "expr_stmt")]
      (is (= (u/then (u/let1 tmp (gget "v")
                             (u/then (py 'py/setitem (gget "a") (gget "i") (u/v tmp))
                                     (gset "b" (u/v tmp))))
                     none)
             (body src))))))


(deftest program-shape-test
  (let [program (lower/lower-packet (parser/parse-source "x = 1\n"))]
    (testing "prelude first, then the module run by py/run-module, with tail
              calls marked"
      (is (= (u/mark-tails
               (u/then prelude/uast
                       (py 'py/run-module
                           (u/lam '[%globals] (u/then (gset "x" (u/lit 1)) none)))))
             program)))
    (testing "yin/def appears only in the prelude"
      (is (not (re-find #"yin/def" (pr-str (-> program :operands second))))))
    (testing "the batch passes the Universal AST's own checks"
      (is (nil? (vm/ast-reserved-defect program)))
      (is (vector? (vm/ast->datoms program))))))


(deftest tail-marks-test
  (let [program (u/mark-tails (u/lam [] (body "while x:\n    pass\n")))
        loop-lam (-> program :body :operands first :operands first)
        continue-app (:consequent (:body loop-lam))]
    (is (true? (:tail? continue-app)) "the sequencing application")
    (is (true? (-> continue-app :operator :body :tail?)) "the recursive call")
    (is (not (:tail? (-> program :body :operands first)))
        "an application that is an operand is never a tail call")))


;; =============================================================================
;; Refusals
;; =============================================================================

(defn- refusal
  [src]
  (try (body src)
       nil
       (catch clojure.lang.ExceptionInfo e
         (select-keys (ex-data e) [:yang.python.antlr/diagnostic :rule :construct]))))


(deftest unsupported-constructs-are-qualified-test
  (doseq [[src rule construct]
          [["import os\n" "import_stmt" "import"]
           ["with f: pass\n" "with_stmt" "with statement"]
           ["def g():\n    yield 1\n" "yield_stmt" "yield"]
           ["x = [i for i in y]\n" "atom" "list comprehension"]
           ["try:\n    pass\nfinally:\n    pass\n" "try_stmt" "finally"]
           ["x = a ** 2\n" "expr" "operator **"]
           ["x = a % 2\n" "expr" "operator %"]
           ["def f(**kw): pass\n" "typedargslist" "**kwargs parameter"]
           ["def f(*a, b): pass\n" "typedargslist" "keyword-only parameters"]
           ["def f(a: int): pass\n" "tfpdef" "parameter annotation"]
           ["g = globals\n" "name" "builtin globals used as a value"]
           ["f(k=1)\n" "argument" "keyword, starred or generator argument"]
           ["x = (1, 2)\n" "testlist_comp" "tuple"]
           ["a, b = 1, 2\n" "testlist_star_expr" "tuple"]
           ["x = a[1:2]\n" "trailer" "slice"]
           ["x = f'{a}'\n" "atom" "f-string"]
           ["x = 1 in y\n" "comp_op" "comparison in"]
           ["class C(A, B): pass\n" "classdef" "multiple inheritance"]
           ["del x\n" "del_stmt" "del statement"]]]
    (testing src
      (is (= {:yang.python.antlr/diagnostic :yang.python.antlr/unsupported,
              :rule rule,
              :construct construct}
             (refusal src))))))


(deftest static-flow-errors-test
  (doseq [[src message]
          [["break\n" "'break' outside loop"]
           ["continue\n" "'continue' not properly in loop"]
           ["return 1\n" "'return' outside function"]
           ["def f(a, a): pass\n" "duplicate argument in function definition"]
           ["def f(a=1, b): pass\n" "non-default argument follows default argument"]
           ["while x:\n    def f():\n        break\n"
            "'break' outside loop"]]]
    (testing src
      (is (= message
             (try (body src)
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-message e))))))))


(deftest every-grammar-rule-is-classified-test
  (let [grammar (set (seq Python3Parser/ruleNames))
        unsupported (set (keys lower/unsupported-rules))]
    (is (= grammar
           (set/union lower/handled-rules lower/consumed-rules unsupported)))
    (is (empty? (set/intersection lower/handled-rules lower/consumed-rules)))
    (is (empty? (set/intersection lower/handled-rules unsupported)))
    (is (empty? (set/intersection lower/consumed-rules unsupported)))))


;; =============================================================================
;; The two stages over streams
;; =============================================================================

(defn- run-stages
  "Source text split into `texts` -> parser stage -> lowering stage."
  [texts]
  (let [src (tu/new-memory-log)
        cst (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (doseq [[i t] (map-indexed vector texts)]
      (stream/append! src {:yang.source/event :yang.source/chunk,
                           :yang.source/unit [:u 1],
                           :yang.source/ordinal i,
                           :yang.source/text t}))
    (stream/append! src {:yang.source/event :yang.source/seal,
                         :yang.source/unit [:u 1],
                         :yang.source/chunk-count (count texts)})
    (parser/step-stage (parser/open-stage src cst))
    (let [s (lower/step-stage (lower/open-stage cst program diagnostics))]
      {:status (:status s),
       :program (tu/drain program),
       :diagnostics (tu/drain diagnostics)})))


(deftest chunk-invariance-through-lowering-test
  (let [src (str "def f(a):\n    s = 'h\u00e9'\n    return a\n"
                 "for i in range(3):\n    print(f(i))\n")
        cuts [[src]
              [(subs src 0 1) (subs src 1)]
              (mapv str src)
              [(subs src 0 13) (subs src 13 14) (subs src 14)]]
        runs (mapv run-stages cuts)]
    (is (= :blocked (:status (first runs))))
    (is (= 1 (count (:program (first runs)))))
    (is (empty? (:diagnostics (first runs))))
    (is (apply = (map :program runs)))
    (is (= (:program (first runs))
           [(vec (vm/ast->datoms (lower/lower-packet (parser/parse-source
                                                       (parser/make-worker)
                                                       [:u 1] src))))]))))


(deftest stage-diagnostics-test
  (testing "a syntax-error packet produces a diagnostic and no program"
    (let [{:keys [program diagnostics]} (run-stages ["def f(:\n"])]
      (is (empty? program))
      (is (= :yang.python.antlr/syntax-error
             (:yang.python.antlr/diagnostic (first diagnostics))))
      (is (seq (:errors (first diagnostics))))))
  (testing "an unsupported construct produces a qualified diagnostic"
    (let [{:keys [program diagnostics]} (run-stages ["import os\n"])]
      (is (empty? program))
      (is (= {:yang.cst/unit [:u 1],
              :yang.python.antlr/diagnostic :yang.python.antlr/unsupported,
              :rule "import_stmt",
              :construct "import",
              :span [0 9],
              :message "Unsupported Python construct: import"}
             (first diagnostics))))))
