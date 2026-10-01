(ns yang.python.antlr.scope-test
  "Binding collection and name resolution: locals, parameters, `global`,
   `nonlocal`, forward capture, class scopes, and the static errors."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.antlr.packet :as packet]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.scope :as scope]))


(defn- analyze
  [src]
  (let [pk (packet/validate! (parser/parse-source src))]
    [pk (scope/analyze pk)]))


(defn- scope-named
  "The scope record of the funcdef or classdef called `nm`."
  [[pk analysis] nm]
  (let [node (first (filter (fn [n]
                              (and (contains? #{"funcdef" "classdef"} (:rule n))
                                   (= nm (scope/simple-name
                                           pk
                                           (first (packet/child-rules pk n "name"))))))
                            (:yang.cst/nodes pk)))]
    (get-in analysis [:scopes (:id node)])))


(defn- resolves
  [[_ analysis :as a] scope-name nm]
  (scope/resolve analysis (:id (scope-named a scope-name)) nm))


(deftest locals-and-params-test
  (let [a (analyze (str "def f(a, b):\n"
                        "    x = 1\n"
                        "    for i in a:\n"
                        "        pass\n"
                        "    try:\n"
                        "        pass\n"
                        "    except E as e:\n"
                        "        pass\n"
                        "    def g():\n"
                        "        z = 1\n"
                        "    class K:\n"
                        "        pass\n"
                        "    y += 1\n"
                        "    b = 2\n"
                        "    a[0] = 1\n"
                        "    q.r = 2\n"))
        f (scope-named a "f")]
    (is (= :function (:kind f)))
    (is (= ["a" "b"] (:params f)))
    (testing "params first, then other bindings in first-occurrence order;
              subscript and attribute targets bind nothing; nested scope
              bodies are not scanned"
      (is (= ["a" "b" "x" "i" "e" "g" "K" "y"] (:locals f))))
    (is (= {:kind :cell} (resolves a "f" "x")))
    (is (= {:kind :global, :declared? false} (resolves a "f" "E")))
    (is (= ["z"] (:locals (scope-named a "g"))))))


(deftest module-names-are-globals-test
  (let [[_ analysis :as a] (analyze "x = 1\ndef f():\n    return x\n")]
    (is (= [] (:locals (get-in analysis [:scopes (:module analysis)]))))
    (is (= #{"x" "f"} (:global-names analysis)))
    (is (= {:kind :global, :declared? true}
           (scope/resolve analysis (:module analysis) "x")))
    (is (= {:kind :global, :declared? true} (resolves a "f" "x")))
    (is (= {:kind :global, :declared? false} (resolves a "f" "len")))))


(deftest global-declaration-test
  (let [[_ analysis :as a] (analyze (str "def f():\n"
                                         "    global t\n"
                                         "    t = 1\n"
                                         "    u = t\n"))]
    (is (= ["u"] (:locals (scope-named a "f"))))
    (is (= #{"t"} (:globals (scope-named a "f"))))
    (is (contains? (:global-names analysis) "t"))
    (is (= {:kind :global, :declared? true} (resolves a "f" "t")))))


(deftest nonlocal-declaration-test
  (let [a (analyze (str "def outer():\n"
                        "    n = 0\n"
                        "    def inner():\n"
                        "        nonlocal n\n"
                        "        n = n + 1\n"
                        "    return inner\n"))]
    (is (= ["n" "inner"] (:locals (scope-named a "outer"))))
    (is (= [] (:locals (scope-named a "inner"))) "nonlocal is not local")
    (is (= {:kind :cell} (resolves a "inner" "n")))))


(deftest forward-capture-test
  (testing "a free name resolves to the enclosing function's local even
            though the assignment comes after the nested def"
    (let [a (analyze (str "def outer():\n"
                          "    def f():\n"
                          "        return x\n"
                          "    x = 5\n"
                          "    return f()\n"))]
      (is (= ["f" "x"] (:locals (scope-named a "outer"))))
      (is (= {:kind :cell} (resolves a "f" "x"))))))


(deftest class-scope-test
  (let [a (analyze (str "x = 1\n"
                        "def wrap():\n"
                        "    y = 1\n"
                        "    class C:\n"
                        "        x = 2\n"
                        "        y = 3\n"
                        "        def m(self):\n"
                        "            return x + y\n"
                        "    return C\n"))
        c (scope-named a "C")]
    (is (= :class (:kind c)))
    (is (= ["x" "y" "m"] (:locals c)))
    (is (= {:kind :class-attr, :scope (:id c)} (resolves a "C" "x")))
    (testing "methods skip the class scope"
      (is (= {:kind :global, :declared? true} (resolves a "m" "x")))
      (is (= {:kind :cell} (resolves a "m" "y"))))))


(deftest lambda-scope-test
  (let [[pk analysis] (analyze "f = lambda a, b: a\n")
        lam (first (filter #(= "lambdef" (:rule %)) (:yang.cst/nodes pk)))]
    (is (= {:id (:id lam), :kind :lambda, :parent (:module analysis),
            :params ["a" "b"], :assigned [], :globals #{}, :nonlocals #{},
            :locals ["a" "b"]}
           (get-in analysis [:scopes (:id lam)])))))


(deftest tuple-star-and-with-targets-test
  (let [a (analyze (str "def f():\n"
                        "    a, (b, *c) = x\n"
                        "    for k, v in y:\n"
                        "        pass\n"
                        "    with m as (p, q):\n"
                        "        pass\n"
                        "    [d] = z\n"))]
    (is (= ["a" "b" "c" "k" "v" "p" "q" "d"] (:locals (scope-named a "f"))))))


(defn- comprehension-scope
  [[pk analysis]]
  (let [node (first (filter #(scope/comprehension? pk %) (:yang.cst/nodes pk)))]
    (get-in analysis [:scopes (:id node)])))


(deftest comprehension-scope-test
  (testing "comprehension targets are local to the comprehension, not the
            function; free names resolve to the enclosing function"
    (let [a (analyze (str "def f():\n"
                          "    y = 1\n"
                          "    return [x + y for x in z for w in x if w]\n"))
          [_ analysis] a
          c (comprehension-scope a)]
      (is (= :comprehension (:kind c)))
      (is (= ["x" "w"] (:locals c)))
      (is (= ["y"] (:locals (scope-named a "f"))))
      (is (= {:kind :cell} (scope/resolve analysis (:id c) "y")))
      (is (= {:kind :global, :declared? false} (scope/resolve analysis (:id c) "z")))))
  (testing "a lambda in the first iterable belongs to the enclosing scope;
            one in the element belongs to the comprehension"
    (let [[pk analysis :as a] (analyze "r = [lambda: x for x in (lambda: q)()]\n")
          c (comprehension-scope a)
          lambdas (filter #(= "lambdef" (:rule %)) (:yang.cst/nodes pk))
          parents (set (map #(get-in analysis [:scopes (:id %) :parent]) lambdas))]
      (is (= #{(:module analysis) (:id c)} parents))))
  (testing "a class body's comprehension skips the class for free names"
    (let [a (analyze "class C:\n    n = 3\n    v = [n for i in range(2)]\n")
          [_ analysis] a
          c (comprehension-scope a)]
      (is (= {:kind :global, :declared? false} (scope/resolve analysis (:id c) "n"))))))


(defn- analysis-error
  [src]
  (try (analyze src)
       nil
       (catch clojure.lang.ExceptionInfo e
         [(ex-message e) (:yang.python.antlr/diagnostic (ex-data e))])))


(deftest static-errors-test
  (is (= ["nonlocal declaration not allowed at module level"
          :yang.python.antlr/syntax]
         (analysis-error "nonlocal x\n")))
  (is (= ["no binding for nonlocal 'x' found" :yang.python.antlr/syntax]
         (analysis-error "def f():\n    nonlocal x\n    x = 1\n")))
  (is (= ["no binding for nonlocal 'x' found" :yang.python.antlr/syntax]
         (analysis-error "x = 1\ndef f():\n    nonlocal x\n")))
  (is (= ["name 'a' is parameter and global" :yang.python.antlr/syntax]
         (analysis-error "def f(a):\n    global a\n"))))


(deftest analysis-is-deterministic-test
  (let [src "def f(a):\n    b = a\n    def g():\n        nonlocal b\n        b = 2\n"]
    (is (= (second (analyze src)) (second (analyze src))))))
