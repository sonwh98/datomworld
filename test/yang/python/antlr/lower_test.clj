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
    [yin.vm.encoder :as encoder]
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
  (py 'py/call f (reduce #(py 'py/vconj %1 %2) (u/lit []) args)))


(defn- builtin
  "A builtin name read: the module dict, then the task's builtins dict, an
   ordinary global read."
  [nm]
  (gget nm))


(defn- fobj
  "A function object with positional `params` and no defaults."
  [nm params args code-body]
  (py 'py/make-function (u/lit nm) (u/lit {:params params}) (u/lit []) (u/lit [])
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
                     (fobj "f" ["a"] args
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
      (is (= (u/then (gset "g" (fobj "g" [] args
                                     (u/then (call (builtin "print")) none)))
                     none)
             (body src))))))


(deftest defaults-and-star-golden-test
  (testing "defaults are evaluated at definition time in the enclosing scope;
            the static spec names the positional, keyword-only and starred
            parameters; the code vector is laid out as they are written"
    (let [src "def f(a, b=k, *rest, c, d=m, **kw):\n    pass\n"
          args (sym "args" src "funcdef")]
      (is (= (u/then
               (gset "f"
                     (py 'py/make-function (u/lit "f")
                         (u/lit {:params ["a" "b"], :star? true, :kwonly ["c" "d"],
                                 :kwstar? true})
                         (py 'py/vconj (u/lit []) (gget "k"))
                         (py 'py/vconj (u/lit [])
                             (py 'py/vconj (py 'py/vconj (u/lit []) (u/lit "d")) (gget "m")))
                         (u/lam [args]
                                (apply u/app (u/lam '[a b rest c d kw] (u/then none none))
                                       (map #(py 'cell/new (py 'py/arg (u/v args) (u/lit %)))
                                            (range 6))))))
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
                     (py 'py/iterable (gget "xs"))
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
  (testing "a clause matches through py/exc-matches; `as e` binds e for the
            handler and unbinds it on every exit"
    (let [src "try:\n    f()\nexcept E as e:\n    raise\nexcept:\n    pass\n"
          exc (sym "exc" src "try_stmt")
          ux (sym "ux" src "except_clause")]
      (is (= (u/then
               (py 'py/try
                   (u/lam [] (call (gget "f")))
                   (u/lam [exc]
                          (u/if-node (py 'py/exc-matches (u/v exc) (gget "E"))
                                     (py 'py/try-finally
                                         (u/lam [] (u/then (gset "e" (u/v exc))
                                                           (py 'py/raise (u/v exc))))
                                         (u/lam [ux] (py 'py/global-del-quiet (u/v '%globals)
                                                         (u/lit {:py/str "e"}))))
                                     none))
                   (u/lam [] none))
               none)
             (body src))))))


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
                            (fobj "m" ["self"] args
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


(deftest try-finally-golden-test
  (testing "finally is a thunk of the exception in flight (or None) run by
            py/try-finally on every exit"
    (let [src "try:\n    f()\nfinally:\n    g()\n"
          fx (sym "fx" src "try_stmt")]
      (is (= (u/then (py 'py/try-finally
                         (u/lam [] (call (gget "f")))
                         (u/lam [fx] (call (gget "g"))))
                     none)
             (body src))))))


(deftest with-golden-test
  (let [src "with m as x:\n    pass\n"
        wv (sym "wv" src "with_item")]
    (is (= (u/then (py 'py/with (gget "m")
                       (u/lam [wv] (u/then (gset "x" (u/v wv)) none)))
                   none)
           (body src)))))


(deftest unpacking-golden-test
  (testing "the value is bound once; py/unpack-star returns before-items, the
            middle as a list, after-items; targets are assigned left to right"
    (let [src "a, *b = v\n"
          tmp (sym "v" src "expr_stmt")
          un (sym "u" src "testlist_star_expr")]
      (is (= (u/then (u/let1 tmp (gget "v")
                             (u/let1 un (py 'py/unpack-star (u/v tmp) (u/lit 1) (u/lit 0))
                                     (u/then (gset "a" (py 'py/arg (u/v un) (u/lit 0)))
                                             (gset "b" (py 'py/arg (u/v un) (u/lit 1))))))
                     none)
             (body src))))))


(deftest comprehension-golden-test
  (testing "the first iterable is evaluated outside; the target is a cell
            local to the comprehension; the result is a fresh list"
    (let [src "[x for x in y]\n"
          fst (sym "first" src "testlist_comp")
          acc (sym "acc" src "testlist_comp")
          x (sym "x" src "exprlist")]
      (is (= (u/then
               (u/let1 fst (gget "y")
                       (u/app (u/lam '[x]
                                     (u/let1 acc (py 'py/list (u/lit []))
                                             (u/then (py 'py/for-each (u/v fst)
                                                         (u/lam [x]
                                                                (u/then (py 'cell/set! (u/v 'x) (u/v x))
                                                                        (py 'py/list-append (u/v acc)
                                                                            (local "x")))))
                                                     (u/v acc))))
                              (py 'cell/new (u/lit :py/unbound))))
               none)
             (body src))))))


(deftest generator-expression-golden-test
  (testing "a generator expression, even as a consumer's sole argument, is
            an anonymous generator passed as an ordinary argument: the
            first iterable goes through py/iter at creation, the target is
            a cell local to its body and each element is yielded"
    (let [src "sum(x for x in y)\n"
          fst (sym "first" src "argument")
          g (sym "gen" src "argument")
          x (sym "x" src "exprlist")]
      (is (= (u/then
               (py 'py/call
                   (gget "sum")
                   (py 'py/vconj (u/lit [])
                       (u/let1 fst (py 'py/iter (gget "y"))
                               (py 'py/make-generator (u/lit "<genexpr>")
                                   (u/lam [g]
                                          (u/app (u/lam '[x]
                                                        (py 'py/for-each (u/v fst)
                                                            (u/lam [x]
                                                                   (u/then (py 'cell/set! (u/v 'x) (u/v x))
                                                                           (py 'py/yield (u/v g)
                                                                               (local "x"))))))
                                                 (py 'cell/new (u/lit :py/unbound))))))))
               none)
             (body src))))))


(deftest keyword-call-golden-test
  (testing "positional values and *splices build the argument vector in
            order; keywords and **splices build the keyword pairs"
    (is (= (u/then (py 'py/call-kw (gget "f")
                       (py 'py/extend (py 'py/vconj (u/lit []) (u/lit 1)) (gget "a"))
                       (py 'py/kw-extend
                           (py 'py/vconj (u/lit [])
                               (py 'py/vconj (py 'py/vconj (u/lit []) (u/lit "k")) (u/lit 2)))
                           (gget "d")))
                   none)
           (body "f(1, *a, k=2, **d)\n")))))


(deftest star-after-keyword-golden-test
  (testing "*iterable may follow name=value (the language reference allows
            it); positional and * arguments are evaluated before keyword
            values, as CPython does"
    (is (= (u/then (py 'py/call-kw (gget "f")
                       (py 'py/extend (u/lit []) (gget "b"))
                       (py 'py/vconj (u/lit [])
                           (py 'py/vconj (py 'py/vconj (u/lit []) (u/lit "a")) (u/lit 1))))
                   none)
           (body "f(a=1, *b)\n")))))


(deftest slice-and-power-golden-test
  (testing "a slice is a value; ** is regrouped to the right"
    (is (= (u/seq-nodes [(py 'py/getitem (gget "x") (py 'py/slice (u/lit 1) none none))
                         (py 'py/pow (gget "a") (py 'py/pow (gget "b") (gget "c")))
                         none])
           (body "x[1:]\na ** b ** c\n")))))


(deftest program-shape-test
  (let [program (lower/lower-packet (parser/parse-source "x = 1\n"))]
    (testing "prelude first, then the module run by py/run-main, with tail
              calls marked"
      (is (= (u/mark-tails
               (u/then prelude/uast
                       (py 'py/run-main
                           (u/lam '[%globals %globals-fn]
                                  (u/then (gset "x" (u/lit 1)) none)))))
             program)))
    (testing "yin/def appears only in the prelude"
      (is (not (re-find #"yin/def" (pr-str (-> program :operands second))))))
    (testing "the batch passes the Universal AST's own checks"
      (is (nil? (vm/ast-reserved-defect program)))
      (is (vector? (vm/ast->datoms program)))))
  (testing "the lowering names no builtin store key: print(len([])) reads
            both names through the module dict and the builtins dict"
    (is (= []
           (->> (tree-seq coll? seq (body "print(len([]))\n"))
                (filter #(and (map? %) (= :variable (:type %))
                              (= "py.b" (namespace (:name %)))))
                vec)))))


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
           ["x = a @ b\n" "expr" "operator @"]
           ["x @= b\n" "augassign" "augmented @="]
           ["def f(a: int): pass\n" "tfpdef" "parameter annotation"]
           ["a[1:2:3] = x\n" "trailer" "extended slice assignment"]
           ["x = a[1:2, 3]\n" "trailer" "multi-dimensional slicing"]
           ["x = {**d}\n" "atom" "dict unpacking in a display"]
           ["x = f'{a}'\n" "atom" "f-string"]
           ["x = 1 <> 2\n" "comp_op" "comparison <>"]
           ["class C(A, B): pass\n" "classdef" "multiple inheritance"]
           ["del x\n" "del_stmt" "del statement"]
           ["assert x\n" "assert_stmt" "assert statement"]]]
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
            "'break' outside loop"]
           ["x = '\\U00110000'\n" "illegal Unicode character in \\U escape"]
           ["a, *b, *c = x\n" "multiple starred expressions in assignment"]
           ["*a = x\n" "starred assignment target must be in a list or tuple"]
           ["f(a=1, a=2)\n" "keyword argument repeated: a"]
           ["f(a=1, 2)\n" "positional argument follows keyword argument"]
           ["f(**d, 1)\n" "positional argument follows keyword argument unpacking"]
           ["f(**d, *a)\n" "iterable argument unpacking follows keyword argument unpacking"]
           ["def f(*, ): pass\n" "named arguments must follow bare *"]
           ["a, b += 1\n" "illegal expression for augmented assignment"]
           ["x = [*a for a in b]\n" "iterable unpacking cannot be used in comprehension"]
           ["x = *a\n" "can't use starred expression here"]
           ["yield 1\n" "'yield' outside function"]
           ["yield from x\n" "'yield' outside function"]
           ["x = [(yield from y) for x in z]\n" "'yield' inside list comprehension"]
           ["class C:\n    yield 1\n" "'yield' outside function"]
           ["x = [(yield x) for x in y]\n" "'yield' inside list comprehension"]
           ["def f():\n    return {(yield) for x in y}\n" "'yield' inside set comprehension"]
           ["x = ((yield) for x in y)\n" "'yield' inside generator expression"]
           ["def f():\n    return list((yield x) for x in y)\n"
            "'yield' inside generator expression"]
           ["def f():\n    return ((yield from z) for x in y)\n"
            "'yield' inside generator expression"]
           ["def f():\n    return (x for x in y if (yield))\n"
            "'yield' inside generator expression"]
           ["def f():\n    def h():\n        pass\n    class C:\n        x = yield\n"
            "'yield' outside function"]]]
    (testing src
      (is (= message
             (try (body src)
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-message e))))))))


(defn- generator-names
  "The names of the generators `src`'s lowering makes."
  [src]
  (set (keep (fn [n]
               (when (and (map? n)
                          (= :application (:type n))
                          (= 'py/make-generator (:name (:operator n))))
                 (:value (first (:operands n)))))
             (tree-seq coll? #(if (map? %) (vals %) (seq %)) (body src)))))


(deftest generator-classification-test
  (testing "a nested definition's header runs in the enclosing scope: a
            yield in a default or a class base makes the enclosing function
            a generator, even when it is the only yield"
    (doseq [[src names]
            [["def g():\n    def h(x=(yield 1)):\n        pass\n" #{"g"}]
             ["def g():\n    f = lambda x=(yield 1): x\n" #{"g"}]
             ["def g():\n    class C((yield 1)):\n        pass\n" #{"g"}]
             ["def g():\n    def h(x=(yield 1)):\n        yield x\n" #{"g" "h"}]
             ["def g():\n    yield 0\n    def h(x=(yield 1)):\n        pass\n" #{"g"}]
             ["def g():\n    def h():\n        yield 1\n" #{"h"}]
             ["def g():\n    f = lambda: (yield 1)\n" #{"<lambda>"}]
             ["def g():\n    yield from h()\n" #{"g"}]
             ;; a generator expression is its own anonymous generator; the
             ;; outermost iterable runs in the enclosing scope, so a yield
             ;; there is the enclosing function's
             ["def g():\n    return (x for x in y)\n" #{"<genexpr>"}]
             ["def g():\n    return (x for x in (yield))\n" #{"g" "<genexpr>"}]]]
      (testing src
        (is (= names (generator-names src))))))
  (testing "a yield only in a nested class body is still outside a function"
    (is (= "'yield' outside function"
           (try (body "def g():\n    class C:\n        x = yield 1\n")
                nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))


(deftest leading-zero-decimal-is-rejected-test
  (testing "0755 never reaches the lowering: the pinned lexer has no token
            for it, so the parser emits a syntax-error packet (the lowering
            also refuses such a literal, should a grammar ever pass one)"
    (is (= :yang.cst/syntax-error
           (:yang.cst/outcome (parser/parse-source "x = 0755\n"))))
    (is (= :yang.cst/ok (:yang.cst/outcome (parser/parse-source "x = 00\n")))))
  (testing "malformed underscores never reach it either (the pinned lexer
            reads no underscore in a number at all; the lowering checks
            Python's grammar itself, int-literal-test)"
    (doseq [src ["x = 1__0\n" "x = 1_\n" "x = 0x__f\n" "x = 0_7\n"]]
      (is (= :yang.cst/syntax-error
             (:yang.cst/outcome (parser/parse-source src)))
          src))))


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
           [(encoder/source-envelope lower/program-medium
                                     [:u 1]
                                     [(lower/lower-packet (parser/parse-source
                                                            (parser/make-worker)
                                                            [:u 1] src))])]))))


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
             (first diagnostics)))))
  (testing "yield at module level, and yield in a comprehension's own scope,
            is one syntax diagnostic and no program"
    (doseq [src ["yield 1\n" "x = [(yield x) for x in y]\n"]]
      (let [{:keys [program diagnostics]} (run-stages [src])]
        (is (empty? program) src)
        (is (= [:yang.python.antlr/syntax]
               (mapv :yang.python.antlr/diagnostic diagnostics))
            src)))))
