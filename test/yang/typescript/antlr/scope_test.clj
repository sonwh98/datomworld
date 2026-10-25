(ns yang.typescript.antlr.scope-test
  "T1 scope analysis (S3): hoisting, block scopes, TDZ classification,
   resolve, and the early errors of the T1 plan (D10), from source."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.antlr.packet :as p]
    [yang.typescript.antlr.cst :as cst]
    [yang.typescript.antlr.scope :as scope]))


(def ^:private worker (cst/make-worker :ts))


(defn- analysis
  [src]
  (let [pk (cst/parse-source worker [:unit 0] src)]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)) src)
    (let [pk (p/validate! pk)]
      [pk (scope/analyze pk)])))


(defn- scopes-of
  [a kind]
  (filterv #(= kind (:kind %)) (vals (:scopes a))))


(defn- early
  [src]
  (try (analysis src)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest hoisting-test
  (let [[_ a] (analysis "var a = 1; function f(x) { var y; let z = 2; return x; }
                         let b = 3; const c = 4; { let d; }")
        module (get (:scopes a) (:module a))
        [f] (scopes-of a :function)]
    (is (= ["a"] (:vars module)))
    (is (= ["f"] (:functions module)))
    (is (= {"b" :let, "c" :const} (:lexical module)))
    (is (= ["x"] (:params f)))
    (is (= ["y"] (:vars f)))
    (is (= {"z" :let} (:lexical f)))
    (is (= 1 (count (scopes-of a :block))))
    (testing "a var in a nested block hoists to the function"
      (let [[_ a] (analysis "function g() { { var q = 1; } if (1) { var r; } }")
            [g] (scopes-of a :function)]
        (is (= ["q" "r"] (:vars g)))))))


(deftest resolve-test
  (let [[_ a] (analysis "let t = 1; var v; function f(p) { return p; } const k = 2;")
        m (:module a)
        [f] (scopes-of a :function)]
    (is (= {:kind :cell, :tdz? true, :const? false} (scope/resolve a m "t")))
    (is (= {:kind :cell, :tdz? true, :const? true} (scope/resolve a m "k")))
    (is (= {:kind :cell, :tdz? false, :const? false} (scope/resolve a m "v")))
    (is (= {:kind :cell, :tdz? false, :const? false} (scope/resolve a m "f")))
    (is (= {:kind :cell, :tdz? false, :const? false}
           (scope/resolve a (:id f) "p")))
    (is (= {:kind :cell, :tdz? true, :const? false}
           (scope/resolve a (:id f) "t")))
    (is (= {:kind :global} (scope/resolve a (:id f) "console")))))


(deftest scope-kinds-test
  (let [[_ a] (analysis "for (let i = 0; i < 2; i++) { } try { } catch (e) { }
                         const h = (x) => x; const g = function (y) { return this; };")]
    (is (= 1 (count (scopes-of a :for))))
    (is (= 1 (count (scopes-of a :catch))))
    (is (= 1 (count (scopes-of a :arrow))))
    (is (= ["e"] (:params (first (scopes-of a :catch)))))
    (let [[g] (scopes-of a :function)]
      (is (= ["y"] (:params g)))
      (is (:uses-this? g)))
    (is (not (:uses-this? (first (scopes-of a :arrow)))))))


(deftest named-function-expression-test
  (let [[_ a] (analysis "const f = function g(n) { return g; };")
        [g] (scopes-of a :function)]
    (is (= "g" (:self g)))
    (is (= {:kind :cell, :tdz? false, :const? true}
           (scope/resolve a (:id g) "g")))
    (is (= {:kind :global} (scope/resolve a (:module a) "g"))))
  (testing "a parameter of the same name shadows the self binding"
    (let [[_ a] (analysis "const f = function g(g) { return g; };")
          [g] (scopes-of a :function)]
      (is (= {:kind :cell, :tdz? false, :const? false}
             (scope/resolve a (:id g) "g"))))))


(deftest arrow-this-test
  (let [[_ a] (analysis "function f() { return () => this; }")
        [f] (scopes-of a :function)]
    (is (:uses-this? f) "an arrow's this is the enclosing function's")))


(deftest early-errors-test
  (doseq [[src msg-part]
          [["let a; let a;" "already been declared"]
           ["const a = 1; var a;" "already been declared"]
           ["var a; let a;" "already been declared"]
           ["function f(x) { let x; }" "already been declared"]
           ["const c;" "Missing initializer"]
           ["break;" "Illegal break"]
           ["continue;" "Illegal continue"]
           ["while (1) { break nope; }" "Undefined label"]
           ["a: { continue a; }" "for continue"]
           ["return 1;" "Illegal return"]
           ["a: a: while (1) { }" "already been declared"]
           ["a: b: a: while (1) { }" "already been declared"]
           ["{ var x; let x; }" "already been declared"]
           ["{ let x; var x; }" "already been declared"]
           ["var x; let x;" "already been declared"]
           ["let x; var x;" "already been declared"]
           ["function f() { { let x; { var x; } } }" "already been declared"]
           ["for (const x; false;) {}" "Missing initializer"]
           ["function f(a, a) {}" "Duplicate parameter"]
           ["(function (a, b, a) {});" "Duplicate parameter"]
           ["((a, a) => a);" "Duplicate parameter"]
           ["function f() { while (1) { function g() { break; } } }"
            "Illegal break"]]]
    (testing src
      (let [d (early src)]
        (is (= :yang.ts/early-error (:yang.ts/diagnostic d)))
        (is (vector? (:span d)))
        (is (some? d))
        (is (re-find (re-pattern msg-part)
                     (try (analysis src)
                          (catch clojure.lang.ExceptionInfo e (ex-message e))))))))
  (testing "valid programs raise nothing"
    (doseq [src ["while (1) { break; }" "a: while (1) { continue a; }"
                 "switch (1) { case 1: break; }" "a: { break a; }"
                 "function f() { return 1; }" "{ let a; } { let a; }"
                 "var a; var a;" "function f() {} function f() {}"
                 "a: { b: while (1) { continue b; } }"
                 "a: b: while (1) { continue a; }"
                 "a: b: while (1) { continue b; }"
                 "a: b: { break a; }"
                 "var x; var x; function x() {}"
                 "for (const x = 0; false;) {}"
                 "function f(a, b) {}"]]
      (is (nil? (early src)) src))))
